package com.codeatlas.parser.ada;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, dependency-free scanner for Ada and SPARK sources.
 *
 * <p>There is no mature offline Ada grammar for the JVM, so rather than pull in a
 * native toolchain (which would break the "no admin, fully offline" requirement)
 * this parser recognises Ada's well-structured declaration syntax with a line
 * scanner and a scope stack. It extracts the structure the platform needs &mdash;
 * packages, subprograms, types, {@code with} dependencies, SPARK contracts,
 * renamings, generic instantiations and exceptions &mdash; each anchored to a line
 * so findings stay traceable.
 *
 * <p>It is intentionally conservative: cross-unit call targets are emitted as
 * unresolved edges for the core Linker, and anything it cannot confidently
 * classify is simply skipped rather than guessed.
 */
public final class AdaLanguageParser implements RepositoryParser {

    static final String LANGUAGE = "ada";

    // Case-insensitive: Ada is not case sensitive.
    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern WITH_CLAUSE = Pattern.compile("^\\s*with\\s+([\\w.]+(\\s*,\\s*[\\w.]+)*)\\s*;", CI);
    private static final Pattern PACKAGE = Pattern.compile("^\\s*(private\\s+)?package\\s+(body\\s+)?([\\w.]+)\\s+is\\b", CI);
    private static final Pattern GENERIC_INSTANCE = Pattern.compile("^\\s*(package|procedure|function)\\s+([\\w.]+)\\s+is\\s+new\\s+([\\w.]+)", CI);
    private static final Pattern PROCEDURE = Pattern.compile("^\\s*(overriding\\s+)?procedure\\s+(\\w+)", CI);
    private static final Pattern FUNCTION = Pattern.compile("^\\s*(overriding\\s+)?function\\s+(\\w+|\"[^\"]+\")", CI);
    private static final Pattern TYPE = Pattern.compile("^\\s*(sub)?type\\s+(\\w+)\\s+is\\s+(.*)", CI);
    private static final Pattern TASK = Pattern.compile("^\\s*task\\s+(type\\s+)?(\\w+)", CI);
    private static final Pattern PROTECTED = Pattern.compile("^\\s*protected\\s+(type\\s+)?(\\w+)", CI);
    private static final Pattern EXCEPTION = Pattern.compile("^\\s*(\\w+)\\s*:\\s*exception\\s*;", CI);
    private static final Pattern RENAMES = Pattern.compile("\\brenames\\s+([\\w.]+)", CI);
    private static final Pattern END_SCOPE = Pattern.compile("^\\s*end\\s+(\\w+)\\s*;", CI);
    private static final Pattern SPARK_PRE = Pattern.compile("\\bPre\\s*=>\\s*(.+?)(?:,\\s*(?:Post|Global|Depends|Contract_Cases)\\b|;|$)", CI);
    private static final Pattern SPARK_POST = Pattern.compile("\\bPost\\s*=>\\s*(.+?)(?:,\\s*(?:Pre|Global|Depends|Contract_Cases)\\b|;|$)", CI);
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)*)\\s*\\(");
    // A statement that is just an identifier and a semicolon is a parameterless call.
    private static final Pattern BARE_CALL = Pattern.compile("^\\s*([A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)*)\\s*;\\s*$");
    // A for/while loop carries exactly one `loop` keyword, so counting `loop` covers
    // every loop form once; `end <construct>` phrases are stripped before counting so
    // `end if` / `end loop` are not mistaken for new decision points.
    private static final Pattern END_CONSTRUCT = Pattern.compile("\\bend\\s+(if|loop|case|record|select|return)\\b", CI);
    private static final Pattern DECISION = Pattern.compile("\\b(if|elsif|when|loop)\\b|\\b(and\\s+then|or\\s+else)\\b", CI);

    private static final Set<String> KEYWORDS = Set.of(
            "abort", "abs", "abstract", "accept", "access", "aliased", "all", "and", "array", "at",
            "begin", "body", "case", "constant", "declare", "delay", "delta", "digits", "do", "else",
            "elsif", "end", "entry", "exception", "exit", "for", "function", "generic", "goto", "if",
            "in", "interface", "is", "limited", "loop", "mod", "new", "not", "null", "of", "or",
            "others", "out", "overriding", "package", "pragma", "private", "procedure", "protected",
            "raise", "range", "record", "rem", "renames", "requeue", "return", "reverse", "select",
            "separate", "some", "subtype", "synchronized", "tagged", "task", "terminate", "then",
            "type", "until", "use", "when", "while", "with", "xor");

    @Override
    public String languageId() {
        return LANGUAGE;
    }

    @Override
    public String displayName() {
        return "Ada / SPARK";
    }

    @Override
    public boolean supports(ParseRequest request) {
        String ext = request.extension();
        return ext.equals("ads") || ext.equals("adb") || ext.equals("ada");
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        ParseResult.Builder out = ParseResult.builder(LANGUAGE, request.relativePath());
        String file = request.relativePath();
        boolean isBody = request.extension().equals("adb");

        Entity fileEntity = Entity.builder(EntityKind.FILE, fileName(file))
                .qualifiedName(file).language(LANGUAGE)
                .location(SourceLocation.ofFile(file))
                .attribute(Entity.Attributes.FILE_HASH, request.contentHash())
                .build();
        out.entity(fileEntity);

        Deque<Scope> scopes = new ArrayDeque<>();
        String[] lines = request.content().split("\n", -1);
        // Pending SPARK contract lines carried onto the next subprogram declaration.
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i];
            String line = AdaLexUtil.strip(raw);
            if (line.isBlank()) {
                continue;
            }

            accumulateDecision(scopes, line);

            Matcher m;
            if ((m = WITH_CLAUSE.matcher(line)).find()) {
                for (String unit : m.group(1).split(",")) {
                    String dep = unit.trim();
                    out.relationship(Relationship.builder(RelationshipKind.IMPORTS, fileEntity.id(), dep)
                            .resolved(false).attribute("typeName", dep)
                            .location(new SourceLocation(file, lineNo, lineNo, 0, 0)).build());
                }
                continue;
            }

            Matcher gi = GENERIC_INSTANCE.matcher(line);
            if (gi.find()) {
                emitGenericInstance(out, scopes, fileEntity, gi, file, lineNo);
                continue;
            }

            if ((m = PACKAGE.matcher(line)).find()) {
                openPackage(out, scopes, fileEntity, m.group(3), file, lineNo, isBody);
                continue;
            }

            if ((m = PROCEDURE.matcher(line)).find()) {
                openSubprogram(out, scopes, fileEntity, EntityKind.PROCEDURE, m.group(2), line, isBody,
                        file, lineNo, contractsFrom(lines, i), signatureProfile(lines, i));
                continue;
            }

            Matcher fm = FUNCTION.matcher(line);
            if (fm.find()) {
                openSubprogram(out, scopes, fileEntity, EntityKind.FUNCTION, fm.group(2), line, isBody,
                        file, lineNo, contractsFrom(lines, i), signatureProfile(lines, i));
                continue;
            }

            if ((m = TYPE.matcher(line)).find()) {
                emitType(out, scopes, fileEntity, m.group(2), m.group(3), file, lineNo, !isBody);
                continue;
            }
            if ((m = TASK.matcher(line)).find()) {
                emitLeaf(out, scopes, fileEntity, EntityKind.TASK, m.group(2), file, lineNo);
                continue;
            }
            if ((m = PROTECTED.matcher(line)).find()) {
                emitLeaf(out, scopes, fileEntity, EntityKind.PROTECTED_TYPE, m.group(2), file, lineNo);
                continue;
            }
            if ((m = EXCEPTION.matcher(line)).find()) {
                emitLeaf(out, scopes, fileEntity, EntityKind.EXCEPTION, m.group(1), file, lineNo);
                continue;
            }

            Matcher rn = RENAMES.matcher(line);
            if (rn.find() && !scopes.isEmpty()) {
                out.relationship(Relationship.builder(RelationshipKind.RENAMES,
                                scopes.peek().id, rn.group(1))
                        .resolved(false).attribute("typeName", rn.group(1)).build());
            }

            extractCalls(out, scopes, line);

            Matcher end = END_SCOPE.matcher(line);
            if (end.find()) {
                closeScope(out, scopes, end.group(1), file, lineNo);
            }
        }

        // Close anything still open at EOF (e.g. missing end).
        while (!scopes.isEmpty()) {
            finalizeScope(out, scopes.pop(), file, lines.length);
        }
        return out.build();
    }

    // ---- scope handling ----

    /** A package or subprogram whose full extent is only known at its {@code end}. */
    private static final class Scope {
        final EntityKind kind;
        final String simpleName;
        final String qualifiedName;
        final String id;
        final int startLine;
        int complexity = 1;
        boolean exposed = true; // packages are visible; body-local subprograms are not
        String adaPart = "spec"; // spec | body — which unit this declaration came from
        String pre;
        String post;

        Scope(EntityKind kind, String simpleName, String qualifiedName, String id, int startLine) {
            this.kind = kind;
            this.simpleName = simpleName;
            this.qualifiedName = qualifiedName;
            this.id = id;
            this.startLine = startLine;
        }

        boolean isSubprogram() {
            return kind == EntityKind.PROCEDURE || kind == EntityKind.FUNCTION;
        }
    }

    private void openPackage(ParseResult.Builder out, Deque<Scope> scopes, Entity file,
                             String name, String path, int lineNo, boolean isBody) {
        Scope parent = scopes.peek();
        String qn = parent != null && parent.kind == EntityKind.PACKAGE
                ? parent.qualifiedName + "." + name : name;
        // Identity is location-independent: a package spec and body share one id.
        String id = Entity.stableId(LANGUAGE, EntityKind.PACKAGE, qn);
        Scope scope = new Scope(EntityKind.PACKAGE, name, qn, id, lineNo);
        scope.adaPart = isBody ? "body" : "spec";
        scopes.push(scope);
        // containment recorded at finalize (need endLine); record parent edge now.
        String containerId = parent != null ? parent.id : file.id();
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, containerId, id).build());
    }

    private void openSubprogram(ParseResult.Builder out, Deque<Scope> scopes, Entity file,
                                EntityKind kind, String name, String line, boolean isBody,
                                String path, int lineNo, Contracts contracts, String profile) {
        Scope parent = scopes.peek();
        // The normalized parameter profile is part of the identity so overloads stay
        // distinct while a spec and its body (identical profile) share one identity.
        String qn = (parent != null ? parent.qualifiedName + "." : "") + name + profile;
        SourceLocation loc = new SourceLocation(path, lineNo, lineNo, 0, 0);
        String id = Entity.stableId(LANGUAGE, kind, qn);
        String containerId = parent != null ? parent.id : file.id();
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, containerId, id).build());

        boolean isBodyDefinition = isBody && line.matches(".*\\bis\\b.*") && !line.matches(".*\\bis\\s+new\\b.*");
        if (isBodyDefinition) {
            Scope scope = new Scope(kind, name, qn, id, lineNo);
            // A subprogram defined in a package body (no matching spec seen) is a
            // body-local helper: not externally exposed, so uncalled ones can surface.
            scope.exposed = false;
            scope.adaPart = "body";
            scope.pre = contracts.pre();
            scope.post = contracts.post();
            scopes.push(scope);
        } else {
            // Spec / declaration: build immediately, with any SPARK contracts attached.
            Entity.Builder b = Entity.builder(kind, name).id(id).qualifiedName(qn).language(LANGUAGE)
                    .location(loc)
                    .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, parent == null || parent.kind == EntityKind.PACKAGE);
            applyPart(b, "spec", loc);
            applyContracts(b, contracts);
            out.entity(b.build());
        }
    }

    /**
     * Records which Ada unit a declaration came from (spec/body) plus its location,
     * so a spec-only or body-only entity honestly reports its evidence even before
     * (or without) a merge, and merges simply OR the flags together.
     */
    private static void applyPart(Entity.Builder b, String part, SourceLocation loc) {
        b.attribute(Entity.Attributes.ADA_PART, part);
        if ("spec".equals(part)) {
            b.attribute(Entity.Attributes.HAS_SPEC, true);
            b.attribute(Entity.Attributes.HAS_BODY, false);
            b.attribute(Entity.Attributes.SPEC_LOCATION, loc.toString());
        } else {
            b.attribute(Entity.Attributes.HAS_BODY, true);
            b.attribute(Entity.Attributes.HAS_SPEC, false);
            b.attribute(Entity.Attributes.BODY_LOCATION, loc.toString());
        }
    }

    private static void applyContracts(Entity.Builder b, Contracts c) {
        if (c.pre() != null) {
            b.attribute("sparkPrecondition", c.pre().trim());
            b.attribute(Entity.Attributes.LANGUAGE_FEATURE, "spark-contract");
        }
        if (c.post() != null) {
            b.attribute("sparkPostcondition", c.post().trim());
            b.attribute(Entity.Attributes.LANGUAGE_FEATURE, "spark-contract");
        }
    }

    private void emitGenericInstance(ParseResult.Builder out, Deque<Scope> scopes, Entity file,
                                     Matcher gi, String path, int lineNo) {
        String name = gi.group(2);
        String template = gi.group(3);
        EntityKind kind = switch (gi.group(1).toLowerCase()) {
            case "procedure" -> EntityKind.PROCEDURE;
            case "function" -> EntityKind.FUNCTION;
            default -> EntityKind.PACKAGE;
        };
        Entity e = emitLeaf(out, scopes, file, kind, name, path, lineNo);
        out.relationship(Relationship.builder(RelationshipKind.INSTANTIATES, e.id(), template)
                .resolved(false).attribute("typeName", template).build());
    }

    private void emitType(ParseResult.Builder out, Deque<Scope> scopes, Entity file,
                          String name, String definition, String path, int lineNo, boolean exposed) {
        String feature = classifyType(definition);
        Scope parent = scopes.peek();
        String qn = (parent != null ? parent.qualifiedName + "." : "") + name;
        SourceLocation loc = new SourceLocation(path, lineNo, lineNo, 0, 0);
        Entity e = Entity.builder(EntityKind.TYPE, name).qualifiedName(qn).language(LANGUAGE)
                .location(loc).attribute(Entity.Attributes.LANGUAGE_FEATURE, feature)
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, exposed).build();
        out.entity(e);
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS,
                parent != null ? parent.id : file.id(), e.id()).build());
    }

    private Entity emitLeaf(ParseResult.Builder out, Deque<Scope> scopes, Entity file,
                            EntityKind kind, String name, String path, int lineNo) {
        Scope parent = scopes.peek();
        String qn = (parent != null ? parent.qualifiedName + "." : "") + name;
        SourceLocation loc = new SourceLocation(path, lineNo, lineNo, 0, 0);
        Entity e = Entity.builder(kind, name).qualifiedName(qn).language(LANGUAGE).location(loc).build();
        out.entity(e);
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS,
                parent != null ? parent.id : file.id(), e.id()).build());
        return e;
    }

    private void closeScope(ParseResult.Builder out, Deque<Scope> scopes, String endName,
                            String path, int lineNo) {
        Scope top = scopes.peek();
        if (top != null && top.simpleName.equalsIgnoreCase(endName)) {
            finalizeScope(out, scopes.pop(), path, lineNo);
        }
    }

    private void finalizeScope(ParseResult.Builder out, Scope scope, String path, int endLine) {
        SourceLocation loc = new SourceLocation(path, scope.startLine, endLine, 0, 0);
        Entity.Builder b = Entity.builder(scope.kind, scope.simpleName).id(scope.id)
                .qualifiedName(scope.qualifiedName).language(LANGUAGE).location(loc)
                .attribute(Entity.Attributes.LINES_OF_CODE, loc.lineSpan())
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, scope.exposed);
        applyPart(b, scope.adaPart, loc);
        if (scope.isSubprogram()) {
            b.attribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, scope.complexity);
        }
        if (scope.pre != null) {
            b.attribute("sparkPrecondition", scope.pre.trim());
            b.attribute(Entity.Attributes.LANGUAGE_FEATURE, "spark-contract");
        }
        if (scope.post != null) {
            b.attribute("sparkPostcondition", scope.post.trim());
            b.attribute(Entity.Attributes.LANGUAGE_FEATURE, "spark-contract");
        }
        out.entity(b.build());
    }

    // ---- detail extraction ----

    /** SPARK pre/postconditions extracted from a subprogram's aspect list. */
    private record Contracts(String pre, String post) {
    }

    /**
     * Joins a subprogram declaration with the following aspect lines (up to the
     * terminating {@code ;}) and extracts SPARK Pre/Post conditions from them.
     */
    private static Contracts contractsFrom(String[] lines, int index) {
        StringBuilder joined = new StringBuilder(AdaLexUtil.strip(lines[index]));
        for (int j = index + 1; j < lines.length && j < index + 10; j++) {
            String l = AdaLexUtil.strip(lines[j]);
            joined.append(' ').append(l);
            if (l.contains(";")) {
                break;
            }
        }
        String text = joined.toString();
        Matcher pre = SPARK_PRE.matcher(text);
        Matcher post = SPARK_POST.matcher(text);
        return new Contracts(pre.find() ? pre.group(1) : null, post.find() ? post.group(1) : null);
    }

    private static final Set<String> PARAM_MODES = Set.of("in", "out", "aliased", "constant");

    /**
     * Extracts a normalized parameter profile such as {@code (Integer)} or
     * {@code (Integer,Float)} from a subprogram declaration, or {@code ""} when it
     * has no parameters. This is what distinguishes overloaded subprograms while
     * keeping a specification and its body (identical profiles) as one identity.
     *
     * <p>Types are normalized to their source spelling with modes ({@code in},
     * {@code out}, {@code aliased}, {@code constant}) and defaults stripped and
     * whitespace collapsed. Full type resolution is out of scope (see
     * KNOWN_LIMITATIONS.md), so the profile is deterministic but not fully qualified.
     */
    private static String signatureProfile(String[] lines, int index) {
        String decl = joinDeclaration(lines, index);
        int open = decl.indexOf('(');
        if (open < 0) {
            return "";
        }
        int depth = 0;
        int close = -1;
        for (int p = open; p < decl.length(); p++) {
            char c = decl.charAt(p);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    close = p;
                    break;
                }
            }
        }
        if (close < 0) {
            return "";
        }
        return normalizeProfile(decl.substring(open + 1, close));
    }

    /** Joins the declaration text up to (but not into) its {@code is} or {@code ;}. */
    private static String joinDeclaration(String[] lines, int index) {
        StringBuilder buf = new StringBuilder();
        for (int j = index; j < lines.length && j < index + 12; j++) {
            buf.append(AdaLexUtil.strip(lines[j])).append(' ');
        }
        String s = buf.toString();
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && c == ';') {
                break;
            }
            if (depth == 0 && isWordAt(s, i, "is")) {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isWordAt(String s, int i, String word) {
        if (!s.regionMatches(true, i, word, 0, word.length())) {
            return false;
        }
        boolean beforeOk = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
        int after = i + word.length();
        boolean afterOk = after >= s.length() || !Character.isLetterOrDigit(s.charAt(after));
        return beforeOk && afterOk;
    }

    private static String normalizeProfile(String params) {
        if (params.isBlank()) {
            return "";
        }
        StringBuilder profile = new StringBuilder("(");
        boolean first = true;
        int depth = 0;
        int start = 0;
        // Split parameter declarations on top-level ';'.
        for (int i = 0; i <= params.length(); i++) {
            char c = i < params.length() ? params.charAt(i) : ';';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && (c == ';' || i == params.length())) {
                String decl = params.substring(start, i);
                start = i + 1;
                int colon = decl.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                int names = 1;
                for (char nc : decl.substring(0, colon).toCharArray()) {
                    if (nc == ',') {
                        names++;
                    }
                }
                String type = cleanType(decl.substring(colon + 1));
                if (type.isEmpty()) {
                    continue;
                }
                for (int n = 0; n < names; n++) {
                    if (!first) {
                        profile.append(',');
                    }
                    profile.append(type);
                    first = false;
                }
            }
        }
        return first ? "" : profile.append(')').toString();
    }

    private static String cleanType(String raw) {
        // Drop any default value, collapse whitespace, strip leading parameter modes.
        String t = raw.split(":=")[0].trim().replaceAll("\\s+", " ");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String mode : PARAM_MODES) {
                if (isWordAt(t, 0, mode) && t.length() > mode.length()) {
                    t = t.substring(mode.length()).trim();
                    changed = true;
                }
            }
        }
        return t;
    }

    private void accumulateDecision(Deque<Scope> scopes, String line) {
        Scope sub = nearestSubprogram(scopes);
        if (sub == null) {
            return;
        }
        String scan = END_CONSTRUCT.matcher(line).replaceAll(" ");
        Matcher m = DECISION.matcher(scan);
        while (m.find()) {
            sub.complexity++;
        }
    }

    private void extractCalls(ParseResult.Builder out, Deque<Scope> scopes, String line) {
        Scope sub = nearestSubprogram(scopes);
        if (sub == null) {
            return;
        }
        Matcher bare = BARE_CALL.matcher(line);
        if (bare.matches()) {
            emitCall(out, sub, bare.group(1));
            return;
        }
        Matcher m = CALL.matcher(line);
        while (m.find()) {
            emitCall(out, sub, m.group(1));
        }
    }

    private void emitCall(ParseResult.Builder out, Scope sub, String full) {
        String simple = full.contains(".") ? full.substring(full.lastIndexOf('.') + 1) : full;
        if (KEYWORDS.contains(simple.toLowerCase()) || simple.equalsIgnoreCase(sub.simpleName)) {
            return;
        }
        out.relationship(Relationship.builder(RelationshipKind.CALLS, sub.id, simple)
                .resolved(false).attribute("callName", simple).build());
    }

    private static Scope nearestSubprogram(Deque<Scope> scopes) {
        for (Scope s : scopes) {
            if (s.isSubprogram()) {
                return s;
            }
        }
        return null;
    }

    private static String classifyType(String definition) {
        String d = definition.toLowerCase();
        if (d.contains("record")) {
            return "record";
        }
        if (d.startsWith("(")) {
            return "enumeration";
        }
        if (d.contains("access")) {
            return "access";
        }
        if (d.contains("new ")) {
            return "derived";
        }
        return "type";
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
