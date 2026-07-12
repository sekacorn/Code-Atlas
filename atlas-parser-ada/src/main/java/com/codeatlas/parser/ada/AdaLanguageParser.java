package com.codeatlas.parser.ada;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    // Object declaration: "Name, Name2 : [constant] Type ..." (package state or subprogram local).
    private static final Pattern VARIABLE_DECL =
            Pattern.compile("^\\s*(\\w+(?:\\s*,\\s*\\w+)*)\\s*:\\s*(constant\\s+)?([\\w.]+)", CI);
    // Assignment statement: "Target :=" (whole-object or dotted component).
    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*([\\w.]+)\\s*:=");
    private static final Pattern BEGIN_LINE = Pattern.compile("^\\s*begin\\b", CI);
    // Dotted identifier not followed by '(' — a qualified reference, not a call.
    private static final Pattern QUALIFIED_REF =
            Pattern.compile("\\b([A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)+)\\b(?!\\s*\\()");
    private static final Pattern RECORD_START = Pattern.compile("\\brecord\\b", CI);
    private static final Pattern RECORD_END = Pattern.compile("\\bend\\s+record\\b", CI);
    private static final Pattern NULL_RECORD = Pattern.compile("\\bnull\\s+record\\b", CI);
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
    public String parserVersion() {
        // 1.1.0: package-state variables, state read/write candidates, qualified
        // call names, call locations, parameter/return type attributes.
        return "1.1.0";
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
        // Package-level state declared in THIS file (lower-cased simple name -> entity),
        // used to detect same-file reads/writes of package state.
        Map<String, Entity> fileState = new LinkedHashMap<>();
        // Depth of `record ... end record` blocks: component declarations inside a
        // record are part of the type, not package state.
        int recordDepth = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i];
            String line = AdaLexUtil.strip(raw);
            if (line.isBlank()) {
                continue;
            }

            if (recordDepth > 0) {
                if (RECORD_END.matcher(line).find()) {
                    recordDepth--;
                }
                continue; // record components are type structure, not statements
            }

            accumulateDecision(scopes, line);

            if (BEGIN_LINE.matcher(line).find()) {
                Scope top = scopes.peek();
                if (top != null) {
                    top.inBody = true;
                }
                continue;
            }

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
                String decl = joinDeclaration(lines, i);
                openSubprogram(out, scopes, fileEntity, EntityKind.PROCEDURE, m.group(2), line, isBody,
                        file, lineNo, contractsFrom(lines, i), signatureProfile(decl),
                        paramNamesOf(decl), null);
                continue;
            }

            Matcher fm = FUNCTION.matcher(line);
            if (fm.find()) {
                String decl = joinDeclaration(lines, i);
                openSubprogram(out, scopes, fileEntity, EntityKind.FUNCTION, fm.group(2), line, isBody,
                        file, lineNo, contractsFrom(lines, i), signatureProfile(decl),
                        paramNamesOf(decl), returnTypeOf(decl));
                continue;
            }

            if ((m = TYPE.matcher(line)).find()) {
                emitType(out, scopes, fileEntity, m.group(2), m.group(3), file, lineNo, !isBody);
                // A record definition opens a component block that must not be
                // mistaken for package state or statements.
                if (RECORD_START.matcher(line).find() && !NULL_RECORD.matcher(line).find()
                        && !RECORD_END.matcher(line).find()) {
                    recordDepth++;
                }
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

            // Object declarations: package-level state variables vs subprogram locals.
            // The line still falls through so initializer calls are extracted below.
            boolean declLine = false;
            Matcher vd = VARIABLE_DECL.matcher(line);
            if (vd.find() && isDeclarableType(vd.group(3))) {
                Scope top = scopes.peek();
                if (top != null && !top.inBody) {
                    declLine = true;
                    if (top.kind == EntityKind.PACKAGE) {
                        boolean constant = vd.group(2) != null;
                        for (String name : vd.group(1).split(",")) {
                            Entity var = emitStateVariable(out, top, name.trim(), vd.group(3),
                                    constant, !isBody, file, lineNo);
                            fileState.put(var.name().toLowerCase(Locale.ROOT), var);
                        }
                    } else if (top.isSubprogram()) {
                        for (String name : vd.group(1).split(",")) {
                            top.locals.add(name.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }

            // Reads and writes of package state (statements in a subprogram body or
            // a package elaboration part).
            Scope active = scopes.peek();
            if (!declLine && active != null && active.inBody) {
                String readRegion = line;
                Matcher asg = ASSIGNMENT.matcher(line);
                if (asg.find()) {
                    emitStateWrite(out, scopes, asg.group(1), file, lineNo);
                    readRegion = line.substring(asg.end());
                }
                emitStateReads(out, scopes, fileState, readRegion, file, lineNo);
            }

            Matcher rn = RENAMES.matcher(line);
            if (rn.find() && !scopes.isEmpty()) {
                out.relationship(Relationship.builder(RelationshipKind.RENAMES,
                                scopes.peek().id, rn.group(1))
                        .resolved(false).attribute("typeName", rn.group(1)).build());
            }

            extractCalls(out, scopes, line, file, lineNo);

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
        String paramTypes;  // normalized profile without parentheses, e.g. "Integer,Float"
        String returnType;  // functions only
        boolean inBody;     // true once the subprogram's own `begin` was seen
        final Set<String> locals = new HashSet<>();       // lower-cased local names + parameters
        final Set<String> touchedState = new HashSet<>(); // dedupe of state read/write emissions

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
                                String path, int lineNo, Contracts contracts, String profile,
                                Set<String> paramNames, String returnType) {
        Scope parent = scopes.peek();
        // The normalized parameter profile is part of the identity so overloads stay
        // distinct while a spec and its body (identical profile) share one identity.
        String qn = (parent != null ? parent.qualifiedName + "." : "") + name + profile;
        SourceLocation loc = new SourceLocation(path, lineNo, lineNo, 0, 0);
        String id = Entity.stableId(LANGUAGE, kind, qn);
        String containerId = parent != null ? parent.id : file.id();
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, containerId, id).build());

        String paramTypes = profile.isEmpty() ? null : profile.substring(1, profile.length() - 1);
        boolean isBodyDefinition = isBody && line.matches(".*\\bis\\b.*") && !line.matches(".*\\bis\\s+new\\b.*");
        if (isBodyDefinition) {
            Scope scope = new Scope(kind, name, qn, id, lineNo);
            // A subprogram defined in a package body (no matching spec seen) is a
            // body-local helper: not externally exposed, so uncalled ones can surface.
            scope.exposed = false;
            scope.adaPart = "body";
            scope.pre = contracts.pre();
            scope.post = contracts.post();
            scope.paramTypes = paramTypes;
            scope.returnType = returnType;
            scope.locals.addAll(paramNames);
            scopes.push(scope);
        } else {
            // Spec / declaration: build immediately, with any SPARK contracts attached.
            Entity.Builder b = Entity.builder(kind, name).id(id).qualifiedName(qn).language(LANGUAGE)
                    .location(loc)
                    .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, parent == null || parent.kind == EntityKind.PACKAGE);
            if (paramTypes != null) {
                b.attribute(Entity.Attributes.PARAM_TYPES, paramTypes);
            }
            if (returnType != null) {
                b.attribute(Entity.Attributes.RETURN_TYPE, returnType);
            }
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
            if (scope.paramTypes != null) {
                b.attribute(Entity.Attributes.PARAM_TYPES, scope.paramTypes);
            }
            if (scope.returnType != null) {
                b.attribute(Entity.Attributes.RETURN_TYPE, scope.returnType);
            }
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
    private static String signatureProfile(String decl) {
        String params = parenRegion(decl);
        return params == null ? "" : normalizeProfile(params);
    }

    /** Lower-cased parameter names of a declaration ({@code A, B : Integer; C : Float}). */
    private static Set<String> paramNamesOf(String decl) {
        String params = parenRegion(decl);
        Set<String> names = new HashSet<>();
        if (params == null) {
            return names;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= params.length(); i++) {
            char c = i < params.length() ? params.charAt(i) : ';';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && (c == ';' || i == params.length())) {
                String one = params.substring(start, i);
                start = i + 1;
                int colon = one.indexOf(':');
                if (colon > 0) {
                    for (String n : one.substring(0, colon).split(",")) {
                        if (!n.isBlank()) {
                            names.add(n.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        return names;
    }

    /** The declared return type of a function declaration, or {@code null}. */
    private static String returnTypeOf(String decl) {
        Matcher m = Pattern.compile("\\breturn\\s+([\\w.]+)", CI).matcher(decl);
        return m.find() ? m.group(1) : null;
    }

    /** The text inside the declaration's top-level parentheses, or {@code null}. */
    private static String parenRegion(String decl) {
        int open = decl.indexOf('(');
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int p = open; p < decl.length(); p++) {
            char c = decl.charAt(p);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return decl.substring(open + 1, p);
                }
            }
        }
        return null;
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

    private void extractCalls(ParseResult.Builder out, Deque<Scope> scopes, String line,
                              String file, int lineNo) {
        Scope sub = nearestSubprogram(scopes);
        if (sub == null) {
            return;
        }
        Matcher bare = BARE_CALL.matcher(line);
        if (bare.matches()) {
            emitCall(out, sub, bare.group(1), file, lineNo);
            return;
        }
        Matcher m = CALL.matcher(line);
        while (m.find()) {
            emitCall(out, sub, m.group(1), file, lineNo);
        }
    }

    private void emitCall(ParseResult.Builder out, Scope sub, String full, String file, int lineNo) {
        String simple = full.contains(".") ? full.substring(full.lastIndexOf('.') + 1) : full;
        if (KEYWORDS.contains(simple.toLowerCase()) || simple.equalsIgnoreCase(sub.simpleName)) {
            return;
        }
        Relationship.Builder b = Relationship.builder(RelationshipKind.CALLS, sub.id, simple)
                .resolved(false)
                .location(new SourceLocation(file, lineNo, lineNo, 0, 0))
                .attribute(EvidenceKeys.CALL_NAME, simple);
        if (full.contains(".")) {
            b.attribute(EvidenceKeys.QUALIFIED_CALL_NAME, full);
        }
        out.relationship(b.build());
    }

    private static Scope nearestSubprogram(Deque<Scope> scopes) {
        for (Scope s : scopes) {
            if (s.isSubprogram()) {
                return s;
            }
        }
        return null;
    }

    // ---- package-state extraction ----

    /** A declarable object type: not a keyword and not an exception declaration. */
    private static boolean isDeclarableType(String typeName) {
        String lower = typeName.toLowerCase(Locale.ROOT);
        return !KEYWORDS.contains(lower) && !lower.equals("exception");
    }

    /** Emits a package-level state variable (or constant) entity. */
    private Entity emitStateVariable(ParseResult.Builder out, Scope pkg, String name, String typeName,
                                     boolean constant, boolean spec, String path, int lineNo) {
        String qn = pkg.qualifiedName + "." + name;
        SourceLocation loc = new SourceLocation(path, lineNo, lineNo, 0, 0);
        Entity.Builder b = Entity.builder(EntityKind.VARIABLE, name).qualifiedName(qn).language(LANGUAGE)
                .location(loc)
                .attribute("variableType", typeName)
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, spec);
        if (constant) {
            b.attribute("constant", true);
        }
        applyPart(b, spec ? "spec" : "body", loc);
        Entity e = b.build();
        out.entity(e);
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, pkg.id, e.id()).build());
        return e;
    }

    /**
     * Emits an unresolved WRITES_TO candidate for an assignment target, unless the
     * target is a known local or parameter. The Ada lineage analyzer resolves the
     * candidate against the model's package-state variables (or drops it silently
     * — assignment to non-state is ordinary code, not a lineage gap).
     */
    private void emitStateWrite(ParseResult.Builder out, Deque<Scope> scopes, String target,
                                String path, int lineNo) {
        Scope from = scopes.peek();
        Scope sub = nearestSubprogram(scopes);
        String first = target.contains(".") ? target.substring(0, target.indexOf('.')) : target;
        if (sub != null && sub.locals.contains(first.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (from == null || !from.touchedState.add("W|" + target.toLowerCase(Locale.ROOT))) {
            return;
        }
        out.relationship(Relationship.builder(RelationshipKind.WRITES_TO, from.id, target)
                .resolved(false)
                .location(new SourceLocation(path, lineNo, lineNo, 0, 0))
                .attribute(EvidenceKeys.STATE_NAME, target)
                .attribute(EvidenceKeys.ENCLOSING_PACKAGE, enclosingPackageQn(scopes))
                .build());
    }

    /**
     * Emits unresolved READS_FROM candidates: same-file package-state names found in
     * the statement (minus locals and the assignment target) and qualified dotted
     * references. Non-matching candidates are dropped by the analyzer.
     */
    private void emitStateReads(ParseResult.Builder out, Deque<Scope> scopes, Map<String, Entity> fileState,
                                String region, String path, int lineNo) {
        Scope from = scopes.peek();
        Scope sub = nearestSubprogram(scopes);
        if (from == null) {
            return;
        }
        for (Entity var : fileState.values()) {
            String lower = var.name().toLowerCase(Locale.ROOT);
            if (sub != null && sub.locals.contains(lower)) {
                continue; // shadowed by a local: honest exclusion
            }
            if (wordMatch(region, var.name())) {
                emitStateRead(out, scopes, from, var.name(), path, lineNo);
            }
        }
        Matcher q = QUALIFIED_REF.matcher(region);
        while (q.find()) {
            String dotted = q.group(1);
            String first = dotted.substring(0, dotted.indexOf('.'));
            if (sub != null && sub.locals.contains(first.toLowerCase(Locale.ROOT))) {
                continue;
            }
            emitStateRead(out, scopes, from, dotted, path, lineNo);
        }
    }

    private void emitStateRead(ParseResult.Builder out, Deque<Scope> scopes, Scope from, String target,
                               String path, int lineNo) {
        if (!from.touchedState.add("R|" + target.toLowerCase(Locale.ROOT))) {
            return;
        }
        out.relationship(Relationship.builder(RelationshipKind.READS_FROM, from.id, target)
                .resolved(false)
                .location(new SourceLocation(path, lineNo, lineNo, 0, 0))
                .attribute(EvidenceKeys.STATE_NAME, target)
                .attribute(EvidenceKeys.ENCLOSING_PACKAGE, enclosingPackageQn(scopes))
                .build());
    }

    private static String enclosingPackageQn(Deque<Scope> scopes) {
        for (Scope s : scopes) {
            if (s.kind == EntityKind.PACKAGE) {
                return s.qualifiedName;
            }
        }
        return "";
    }

    /** Case-insensitive whole-word occurrence check without per-call regex compilation. */
    private static boolean wordMatch(String text, String word) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerWord = word.toLowerCase(Locale.ROOT);
        int idx = 0;
        while ((idx = lowerText.indexOf(lowerWord, idx)) >= 0) {
            boolean beforeOk = idx == 0 || !isIdentChar(lowerText.charAt(idx - 1));
            int after = idx + lowerWord.length();
            boolean afterOk = after >= lowerText.length() || !isIdentChar(lowerText.charAt(after));
            // A dotted suffix/prefix means it is part of a qualified name, handled separately.
            boolean notQualified = (idx == 0 || lowerText.charAt(idx - 1) != '.')
                    && (after >= lowerText.length() || lowerText.charAt(after) != '.');
            if (beforeOk && afterOk && notQualified) {
                return true;
            }
            idx = after;
        }
        return false;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
