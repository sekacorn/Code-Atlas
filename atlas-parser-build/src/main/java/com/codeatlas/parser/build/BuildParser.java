package com.codeatlas.parser.build;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseIssue;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;

import java.util.List;

/**
 * Parses build files — Maven {@code pom.xml}, Gradle {@code build.gradle[.kts]} /
 * {@code settings.gradle[.kts]}, and GNAT {@code .gpr} — into {@code MODULE}
 * entities with their declared dependencies and main units.
 *
 * <p>Why this matters: build files answer "what are the modules, what does each
 * depend on, and which units are real entry points?". A GNAT project's
 * {@code for Main use (...)} is a <em>declared</em> entry point, so a main unit is
 * never mistaken for dead code and never has to be inferred from naming.
 *
 * <p>Nothing is executed and nothing is fetched: no Maven/Gradle resolution, no
 * script evaluation. Only what a file literally declares is recorded, and each
 * format's limits are documented on its extractor.
 *
 * <p>External dependencies are recorded as <b>unresolved</b> {@code DEPENDS_ON}
 * edges carrying their coordinate, matching how the platform treats every other
 * external reference — it never fabricates nodes for things outside the repository.
 * The Linker turns a coordinate into a resolved module edge when that module is in
 * this repository.
 */
public final class BuildParser implements RepositoryParser {

    static final String LANGUAGE = "build";
    static final String VERSION = "1.0.0";

    @Override
    public String languageId() {
        return LANGUAGE;
    }

    @Override
    public String displayName() {
        return "Build files (Maven / Gradle / GNAT project)";
    }

    @Override
    public String parserVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(ParseRequest request) {
        return BuildFiles.isBuildFile(request.relativePath());
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String file = request.relativePath();
        ParseResult.Builder out = ParseResult.builder(LANGUAGE, file);
        String system = BuildFiles.systemOf(file);
        if (system == null) {
            return out.build();
        }
        try {
            switch (system) {
                case BuildFiles.MAVEN -> maven(request, out, file);
                case BuildFiles.GRADLE -> gradle(request, out, file);
                case BuildFiles.GNAT -> gnat(request, out, file);
                default -> {
                }
            }
        } catch (Exception e) {
            // A malformed build file must never fail the scan, but its facts are lost
            // — that is a parse failure, so coverage counts the file as failed rather
            // than silently skipped.
            out.issue(ParseIssue.error("Build file could not be parsed: " + e.getMessage(), 0));
        }
        return out.build();
    }

    // ---- Maven ----

    private void maven(ParseRequest request, ParseResult.Builder out, String file) throws Exception {
        MavenPomExtractor.Pom pom = MavenPomExtractor.parse(request.content());
        if (pom.artifactId().isEmpty()) {
            out.issue(ParseIssue.warning("pom.xml declares no artifactId; no module recorded", 1));
            return;
        }
        String key = pom.groupId().isEmpty() ? pom.artifactId() : pom.groupId() + ":" + pom.artifactId();
        Entity module = module(key, pom.artifactId(), file, BuildFiles.MAVEN)
                .attribute("groupId", pom.groupId())
                .attribute("artifactId", pom.artifactId())
                .attribute("moduleVersion", pom.version())
                .attribute("packaging", pom.packaging())
                .build();
        emitModule(out, module, file);
        for (MavenPomExtractor.Dep d : pom.dependencies()) {
            dependsOn(out, module.id(), d.key(), file, 1, d.version(), d.scope());
        }
    }

    // ---- Gradle ----

    private void gradle(ParseRequest request, ParseResult.Builder out, String file) {
        GradleExtractor.GradleBuild build = GradleExtractor.parse(request.content());
        // A Gradle module's identity is its rootProject.name when declared, else the
        // directory holding the script (Gradle's own default), else the repo root.
        String dir = BuildFiles.directoryOf(file);
        String name = build.rootName() != null ? build.rootName()
                : (dir.isEmpty() ? "root" : dir.substring(dir.lastIndexOf('/') + 1));
        String key = build.group() != null && !build.group().isEmpty() ? build.group() + ":" + name : name;

        Entity.Builder b = module(key, name, file, BuildFiles.GRADLE);
        if (build.group() != null) {
            b.attribute("groupId", build.group());
        }
        if (build.version() != null) {
            b.attribute("moduleVersion", build.version());
        }
        if (!build.includes().isEmpty()) {
            b.attribute("includes", String.join(",", build.includes()));
        }
        Entity module = b.build();
        emitModule(out, module, file);
        for (GradleExtractor.GradleDep d : build.dependencies()) {
            dependsOn(out, module.id(), d.target(), file, d.line(), "", d.configuration());
        }
    }

    // ---- GNAT ----

    private void gnat(ParseRequest request, ParseResult.Builder out, String file) {
        GnatProjectExtractor.GnatProject project = GnatProjectExtractor.parse(request.content());
        if (project.name() == null) {
            out.issue(ParseIssue.warning("no 'project X is' declaration found; no module recorded", 1));
            return;
        }
        Entity.Builder b = module(project.name(), project.name(), file, BuildFiles.GNAT);
        if (!project.sourceDirs().isEmpty()) {
            b.attribute("sourceDirs", String.join(",", project.sourceDirs()));
        }
        if (!project.mains().isEmpty()) {
            b.attribute("mains", String.join(",",
                    project.mains().stream().map(GnatProjectExtractor.Main::unit).toList()));
        }
        Entity module = b.build();
        emitModule(out, module, file);

        // A declared main unit is a real, build-declared entry point. The Linker
        // resolves the unit name to the Ada subprogram that defines it.
        for (GnatProjectExtractor.Main main : project.mains()) {
            out.relationship(Relationship.builder(RelationshipKind.DECLARES_MAIN, module.id(), main.unit())
                    .resolved(false)
                    .status(ResolutionStatus.DISCOVERED)
                    .location(new SourceLocation(file, main.line(), main.line(), 0, 0))
                    .attribute(EvidenceKeys.CALL_NAME, main.unit())
                    .attribute(EvidenceKeys.RULE_ID, "ATLAS-BUILD-GNAT-MAIN-001")
                    .attribute(EvidenceKeys.ANALYZER_ID, LANGUAGE + "/" + VERSION)
                    .attribute(EvidenceKeys.CONFIDENCE, "1.00")
                    .build());
        }
        for (String withed : project.withedProjects()) {
            dependsOn(out, module.id(), withed, file, 1, "", "with");
        }
    }

    // ---- shared emission ----

    private Entity.Builder module(String key, String name, String file, String system) {
        return Entity.builder(EntityKind.MODULE, name)
                .qualifiedName(key)
                .language(LANGUAGE)
                .location(SourceLocation.ofFile(file))
                .attribute("buildSystem", system)
                .attribute("buildFile", file)
                .attribute("moduleDir", BuildFiles.directoryOf(file));
    }

    private void emitModule(ParseResult.Builder out, Entity module, String file) {
        out.entity(module);
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, "file:" + file, module.id()).build());
    }

    /**
     * Records a declared dependency as an unresolved edge carrying its coordinate.
     * The Linker resolves it to an in-repository module when one matches; otherwise
     * it stays unresolved — an honest external dependency, never a fabricated node.
     */
    private void dependsOn(ParseResult.Builder out, String moduleId, String target, String file,
                           int line, String version, String scope) {
        Relationship.Builder r = Relationship.builder(RelationshipKind.DEPENDS_ON, moduleId, target)
                .resolved(false)
                .status(ResolutionStatus.DISCOVERED)
                .location(new SourceLocation(file, line, line, 0, 0))
                .attribute(EvidenceKeys.TYPE_NAME, target)
                .attribute(EvidenceKeys.RULE_ID, "ATLAS-BUILD-DEPENDENCY-001")
                .attribute(EvidenceKeys.ANALYZER_ID, LANGUAGE + "/" + VERSION)
                .attribute("scope", scope);
        if (version != null && !version.isEmpty()) {
            r.attribute("declaredVersion", version);
        }
        out.relationship(r.build());
    }
}
