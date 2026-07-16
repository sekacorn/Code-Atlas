package com.codeatlas.parser.java;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseIssue;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts the Java structure of a single {@code .java} file into model entities
 * and relationships.
 *
 * <p>Parsing is per-file and does not require the project's full classpath, so it
 * works on arbitrary repositories offline. Cross-file targets (method calls,
 * supertypes, imports) are emitted as <em>unresolved</em> relationships carrying a
 * symbolic name; the core Linker resolves them once every file has been parsed.
 * This keeps parsers stateless and parallel-safe.
 */
public final class JavaLanguageParser implements RepositoryParser {

    static final String LANGUAGE = "java";

    /** Annotations that expose a member to a framework/runtime, so it is not dead. */
    private static final Set<String> EXPOSING_ANNOTATIONS = Set.of(
            "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "ParameterizedTest",
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping",
            "Bean", "Component", "Service", "Repository", "Controller", "RestController", "Configuration",
            "Autowired", "Inject", "EventListener", "Scheduled", "PostConstruct", "PreDestroy",
            "Entity", "Override", "WebMethod"
    );

    @Override
    public String languageId() {
        return LANGUAGE;
    }

    @Override
    public String displayName() {
        return "Java (JavaParser)";
    }

    @Override
    public String parserVersion() {
        // 1.1.0: lineage extraction (endpoints, JPA, DI receivers, type flow).
        return "1.1.0";
    }

    @Override
    public boolean supports(ParseRequest request) {
        return "java".equals(request.extension());
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        ParseResult.Builder out = ParseResult.builder(LANGUAGE, request.relativePath());
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        com.github.javaparser.ParseResult<CompilationUnit> parsed;
        try {
            parsed = parser.parse(request.content());
        } catch (RuntimeException e) {
            out.issue(ParseIssue.error("Java parse failed: " + e.getMessage(), 0));
            return out.build();
        }

        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            parsed.getProblems().stream().limit(20).forEach(p ->
                    out.issue(ParseIssue.error(p.getMessage(),
                            p.getLocation().flatMap(l -> l.getBegin().getRange())
                                    .map(r -> r.begin.line).orElse(0))));
            return out.build();
        }

        CompilationUnit cu = parsed.getResult().get();
        String file = request.relativePath();
        Context ctx = new Context(file, out);

        // File entity (physical source of everything below it).
        Entity fileEntity = Entity.builder(EntityKind.FILE, fileName(file))
                .qualifiedName(file)
                .language(LANGUAGE)
                .location(SourceLocation.ofFile(file))
                .attribute(Entity.Attributes.FILE_HASH, request.contentHash())
                .build();
        out.entity(fileEntity);

        // Package entity (logical grouping; de-duplicated by the model across files).
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");
        Entity packageEntity = null;
        if (!packageName.isEmpty()) {
            packageEntity = Entity.builder(EntityKind.PACKAGE, simpleName(packageName))
                    .qualifiedName(packageName)
                    .language(LANGUAGE)
                    .build();
            out.entity(packageEntity);
        }

        for (TypeDeclaration<?> type : cu.getTypes()) {
            Entity typeEntity = processType(type, packageName, ctx);
            if (typeEntity != null) {
                out.relationship(contains(fileEntity.id(), typeEntity.id()));
                if (packageEntity != null) {
                    out.relationship(contains(packageEntity.id(), typeEntity.id()));
                }
            }
        }

        // Imports -> unresolved IMPORTS edges from the file.
        cu.getImports().forEach(imp -> {
            String imported = imp.getNameAsString();
            out.relationship(Relationship.builder(RelationshipKind.IMPORTS, fileEntity.id(), imported)
                    .resolved(false)
                    .attribute("typeName", imported)
                    .build());
        });

        // Call edges (second pass, now that callable ids are known).
        extractCalls(cu, ctx);

        return out.build();
    }

    /** Per-file parse state: the file path, output builder, and callable-id lookup. */
    private static final class Context {
        final String file;
        final ParseResult.Builder out;
        final Map<Node, String> callableIds = new IdentityHashMap<>();

        Context(String file, ParseResult.Builder out) {
            this.file = file;
            this.out = out;
        }
    }

    private Entity processType(TypeDeclaration<?> type, String packageName, Context ctx) {
        EntityKind kind = kindOf(type);
        String qn = type.getFullyQualifiedName()
                .orElse(packageName.isEmpty() ? type.getNameAsString()
                        : packageName + "." + type.getNameAsString());
        SourceLocation loc = locationOf(type, ctx.file);

        SpringLineageExtractor.TypeLineage lineage = SpringLineageExtractor.extractType(type);

        boolean exposed = isPublic(type) || hasExposingAnnotation(type.getAnnotations());
        Entity.Builder tb = Entity.builder(kind, type.getNameAsString())
                .qualifiedName(qn)
                .language(LANGUAGE)
                .location(loc)
                .attribute(Entity.Attributes.VISIBILITY, visibilityOf(type))
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, exposed)
                .attribute(Entity.Attributes.LINES_OF_CODE, loc != null ? loc.lineSpan() : 0);
        if (lineage.role() != null) {
            tb.attribute(Entity.Attributes.ROLE, lineage.role());
        }
        if (lineage.jpaEntity()) {
            tb.attribute(Entity.Attributes.JPA_ENTITY, true);
            if (lineage.tableName() != null && !lineage.tableName().unresolved()) {
                tb.attribute(Entity.Attributes.JPA_TABLE_NAME, lineage.tableName().path());
            }
        }
        if (lineage.springDataRepository()) {
            tb.attribute(Entity.Attributes.SPRING_DATA_REPOSITORY, true);
            if (lineage.managedEntityType() != null) {
                tb.attribute(Entity.Attributes.MANAGED_ENTITY_TYPE, lineage.managedEntityType());
            }
        }
        Entity typeEntity = tb.build();
        ctx.out.entity(typeEntity);

        // Inheritance / realisation (unresolved by simple/qualified name).
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            for (ClassOrInterfaceType ext : cid.getExtendedTypes()) {
                ctx.out.relationship(Relationship.builder(RelationshipKind.INHERITS,
                                typeEntity.id(), ext.getNameAsString())
                        .resolved(false).attribute("typeName", ext.getNameWithScope()).build());
            }
            for (ClassOrInterfaceType impl : cid.getImplementedTypes()) {
                ctx.out.relationship(Relationship.builder(RelationshipKind.IMPLEMENTS,
                                typeEntity.id(), impl.getNameAsString())
                        .resolved(false).attribute("typeName", impl.getNameWithScope()).build());
            }
        }

        // Members.
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration md) {
                Entity method = processCallable(md, typeEntity, qn, ctx, EntityKind.METHOD);
                emitEndpoint(md, typeEntity, method, lineage.basePath(), ctx);
            } else if (member instanceof ConstructorDeclaration cd) {
                processCallable(cd, typeEntity, qn, ctx, EntityKind.CONSTRUCTOR);
            } else if (member instanceof FieldDeclaration fd) {
                processField(fd, typeEntity, qn, ctx);
            } else if (member instanceof TypeDeclaration<?> nested) {
                Entity nestedEntity = processType(nested, packageName, ctx);
                if (nestedEntity != null) {
                    ctx.out.relationship(contains(typeEntity.id(), nestedEntity.id()));
                }
            }
        }
        return typeEntity;
    }

    private Entity processCallable(CallableDeclaration<?> callable, Entity owner, String ownerQn,
                                   Context ctx, EntityKind kind) {
        String params = callable.getParameters().stream()
                .map(p -> p.getType().asString().replaceAll("\\s+", ""))
                .collect(Collectors.joining(","));
        String signature = callable.getNameAsString() + "(" + params + ")";
        String qn = ownerQn + "#" + signature;
        SourceLocation loc = locationOf(callable, ctx.file);

        int complexity;
        if (callable instanceof MethodDeclaration m) {
            complexity = m.getBody().map(ComplexityVisitor::complexity).orElse(1);
        } else if (callable instanceof ConstructorDeclaration c) {
            complexity = ComplexityVisitor.complexity(c.getBody());
        } else {
            complexity = 1;
        }

        boolean exposed = isPublic(callable)
                || hasExposingAnnotation(callable.getAnnotations())
                || isMainMethod(callable)
                || owner.kind() == EntityKind.INTERFACE;

        Entity.Builder b = Entity.builder(kind, callable.getNameAsString())
                .qualifiedName(qn)
                .language(LANGUAGE)
                .location(loc)
                .attribute(Entity.Attributes.SIGNATURE, signature)
                .attribute(Entity.Attributes.VISIBILITY, visibilityOf(callable))
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, exposed)
                .attribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, complexity)
                .attribute(Entity.Attributes.LINES_OF_CODE, loc != null ? loc.lineSpan() : 0);
        if (!params.isEmpty()) {
            b.attribute(Entity.Attributes.PARAM_TYPES, params);
        }
        if (callable instanceof MethodDeclaration md) {
            b.attribute(Entity.Attributes.RETURN_TYPE, md.getType().asString());
            String normalized = SpringLineageExtractor.normalizeReturnType(md.getType());
            if (!normalized.equals("void")) {
                b.attribute(Entity.Attributes.RETURN_TYPE_NORMALIZED, normalized);
            }
            // A 'native' method is a JNI boundary to non-Java code (C/C++/Ada via a
            // C shim) — the crispest evidence for Java↔Ada boundary discovery.
            if (md.hasModifier(Modifier.Keyword.NATIVE)) {
                b.attribute(Entity.Attributes.NATIVE_METHOD, true);
            }
        }
        Entity entity = b.build();
        ctx.out.entity(entity);
        ctx.out.relationship(contains(owner.id(), entity.id()));
        ctx.callableIds.put(callable, entity.id());

        // Type usage: return type and parameter types reference their declared types.
        if (callable instanceof MethodDeclaration md) {
            emitTypeReference(owner, md.getType().asString(), ctx);
        }
        callable.getParameters().forEach(p -> emitTypeReference(owner, p.getType().asString(), ctx));
        return entity;
    }

    /**
     * Emits an ENDPOINT entity plus its discovery edges when {@code method} carries
     * an HTTP-verb mapping annotation. The endpoint's identity is its verb and
     * normalized path ({@code java:endpoint:POST:/customers}); an unresolved path
     * keeps a deterministic unresolved marker instead of a guessed value.
     */
    private void emitEndpoint(MethodDeclaration md, Entity owner, Entity handler,
                              SpringLineageExtractor.PathValue basePath, Context ctx) {
        var info = SpringLineageExtractor.extractEndpoint(md, basePath).orElse(null);
        if (info == null) {
            return;
        }
        SourceLocation loc = locationOf(md, ctx.file);
        Entity.Builder eb = Entity.builder(EntityKind.ENDPOINT, info.verb() + " " + info.path())
                .qualifiedName(info.verb() + ":" + info.path())
                .language(LANGUAGE)
                .location(loc)
                .attribute(Entity.Attributes.HTTP_METHOD, info.verb())
                .attribute(Entity.Attributes.HTTP_PATH, info.path())
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, true);
        if (info.pathUnresolved()) {
            eb.attribute(Entity.Attributes.HTTP_PATH_UNRESOLVED, true);
        }
        if (info.requestBodyType() != null) {
            eb.attribute(Entity.Attributes.REQUEST_BODY_TYPE, info.requestBodyType());
        }
        if (info.returnTypeNormalized() != null && !info.returnTypeNormalized().equals("void")) {
            eb.attribute(Entity.Attributes.RETURN_TYPE_NORMALIZED, info.returnTypeNormalized());
        }
        if (info.httpParams() != null) {
            eb.attribute(Entity.Attributes.HTTP_PARAMS, info.httpParams());
        }
        if (info.validated()) {
            eb.attribute(Entity.Attributes.VALIDATED, true);
        }
        Entity endpoint = eb.build();
        ctx.out.entity(endpoint);

        ctx.out.relationship(Relationship.builder(RelationshipKind.EXPOSES, owner.id(), endpoint.id())
                .status(com.codeatlas.model.ResolutionStatus.DISCOVERED)
                .location(loc)
                .attribute(EvidenceKeys.RULE_ID, "ATLAS-LINEAGE-ENDPOINT-001")
                .attribute(EvidenceKeys.RULE_VERSION, "1")
                .attribute(EvidenceKeys.ANALYZER_ID, LANGUAGE + "/" + parserVersion())
                .attribute(EvidenceKeys.CONFIDENCE, "1.00")
                .build());
        ctx.out.relationship(Relationship.builder(RelationshipKind.INVOKES, endpoint.id(), handler.id())
                .status(com.codeatlas.model.ResolutionStatus.DISCOVERED)
                .location(loc)
                .attribute(EvidenceKeys.RULE_ID, "ATLAS-LINEAGE-ENDPOINT-001")
                .attribute(EvidenceKeys.RULE_VERSION, "1")
                .attribute(EvidenceKeys.ANALYZER_ID, LANGUAGE + "/" + parserVersion())
                .attribute(EvidenceKeys.CONFIDENCE, "1.00")
                .build());
    }

    private void processField(FieldDeclaration fd, Entity owner, String ownerQn, Context ctx) {
        for (VariableDeclarator var : fd.getVariables()) {
            String qn = ownerQn + "#" + var.getNameAsString();
            Entity field = Entity.builder(EntityKind.FIELD, var.getNameAsString())
                    .qualifiedName(qn)
                    .language(LANGUAGE)
                    .location(locationOf(fd, ctx.file))
                    .attribute(Entity.Attributes.VISIBILITY, visibilityOf(fd))
                    .attribute("fieldType", var.getType().asString())
                    .build();
            ctx.out.entity(field);
            ctx.out.relationship(contains(owner.id(), field.id()));
            emitTypeReference(owner, var.getType().asString(), ctx);
        }
    }

    /**
     * Emits an unresolved REFERENCES edge from {@code owner} to a used type. Generic
     * arguments, arrays and varargs are stripped to the base type name, and Java
     * primitives / {@code void} are ignored (they are never model entities).
     */
    private void emitTypeReference(Entity owner, String typeText, Context ctx) {
        String base = typeText;
        int lt = base.indexOf('<');
        if (lt >= 0) {
            base = base.substring(0, lt);
        }
        base = base.replace("[]", "").replace("...", "").trim();
        if (base.isEmpty() || PRIMITIVES.contains(base)) {
            return;
        }
        String simple = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
        ctx.out.relationship(Relationship.builder(RelationshipKind.REFERENCES, owner.id(), simple)
                .resolved(false).attribute("typeName", base).build());
    }

    private static final Set<String> PRIMITIVES = Set.of(
            "void", "boolean", "byte", "short", "int", "long", "char", "float", "double", "var");

    private void extractCalls(CompilationUnit cu, Context ctx) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String callerId = enclosingCallableId(call, ctx);
            if (callerId == null) {
                continue;
            }
            Relationship.Builder rb = Relationship.builder(RelationshipKind.CALLS,
                            callerId, call.getNameAsString())
                    .resolved(false)
                    .location(locationOf(call, ctx.file))
                    .attribute(EvidenceKeys.CALL_NAME, call.getNameAsString())
                    .attribute(EvidenceKeys.ARG_COUNT, Integer.toString(call.getArguments().size()));
            receiverNameOf(call).ifPresent(r -> rb.attribute(EvidenceKeys.RECEIVER_NAME, r));
            ctx.out.relationship(rb.build());
        }
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            String callerId = enclosingCallableId(creation, ctx);
            if (callerId == null) {
                continue;
            }
            ctx.out.relationship(Relationship.builder(RelationshipKind.INSTANTIATES,
                            callerId, creation.getType().getNameAsString())
                    .resolved(false)
                    .attribute("typeName", creation.getType().getNameWithScope())
                    .build());
        }
    }

    /**
     * The simple receiver of a call, when it is a plain name: {@code svc.run()} →
     * {@code svc}, {@code this.svc.run()} → {@code svc}, {@code Types.of()} →
     * {@code Types}. Chained or complex receivers yield empty — the lineage
     * analyzer treats those conservatively rather than guessing.
     */
    private static Optional<String> receiverNameOf(MethodCallExpr call) {
        var scope = call.getScope().orElse(null);
        if (scope == null) {
            return Optional.empty(); // implicit this: same-owner call
        }
        if (scope instanceof com.github.javaparser.ast.expr.NameExpr name) {
            return Optional.of(name.getNameAsString());
        }
        if (scope instanceof com.github.javaparser.ast.expr.FieldAccessExpr field
                && field.getScope() instanceof com.github.javaparser.ast.expr.ThisExpr) {
            return Optional.of(field.getNameAsString());
        }
        return Optional.empty();
    }

    private String enclosingCallableId(Node node, Context ctx) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node p = parent.get();
            String id = ctx.callableIds.get(p);
            if (id != null) {
                return id;
            }
            parent = p.getParentNode();
        }
        return null;
    }

    // ---- small helpers ----

    private static Relationship contains(String fromId, String toId) {
        return Relationship.builder(RelationshipKind.CONTAINS, fromId, toId).build();
    }

    private static EntityKind kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? EntityKind.INTERFACE : EntityKind.CLASS;
        }
        if (type instanceof EnumDeclaration) {
            return EntityKind.ENUM;
        }
        if (type instanceof RecordDeclaration) {
            return EntityKind.RECORD;
        }
        if (type instanceof AnnotationDeclaration) {
            return EntityKind.ANNOTATION;
        }
        return EntityKind.CLASS;
    }

    private static boolean isMainMethod(CallableDeclaration<?> c) {
        return c instanceof MethodDeclaration md
                && md.getNameAsString().equals("main")
                && md.isStatic()
                && md.isPublic();
    }

    private static boolean isPublic(Node node) {
        if (node instanceof com.github.javaparser.ast.nodeTypes.modifiers.NodeWithAccessModifiers<?> n) {
            return n.getAccessSpecifier() == AccessSpecifier.PUBLIC;
        }
        return false;
    }

    private static String visibilityOf(Node node) {
        if (node instanceof com.github.javaparser.ast.nodeTypes.modifiers.NodeWithAccessModifiers<?> n) {
            AccessSpecifier a = n.getAccessSpecifier();
            return switch (a) {
                case PUBLIC -> "public";
                case PROTECTED -> "protected";
                case PRIVATE -> "private";
                case NONE -> "package";
            };
        }
        return "package";
    }

    private static boolean hasExposingAnnotation(NodeList<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(a -> EXPOSING_ANNOTATIONS.contains(a.getNameAsString()));
    }

    private static SourceLocation locationOf(Node node, String file) {
        return node.getRange()
                .map((Range r) -> new SourceLocation(file, r.begin.line, r.end.line, r.begin.column, r.end.column))
                .orElse(SourceLocation.ofFile(file));
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
