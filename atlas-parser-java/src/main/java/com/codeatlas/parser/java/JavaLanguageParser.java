package com.codeatlas.parser.java;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
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

        boolean exposed = isPublic(type) || hasExposingAnnotation(type.getAnnotations());
        Entity typeEntity = Entity.builder(kind, type.getNameAsString())
                .qualifiedName(qn)
                .language(LANGUAGE)
                .location(loc)
                .attribute(Entity.Attributes.VISIBILITY, visibilityOf(type))
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, exposed)
                .attribute(Entity.Attributes.LINES_OF_CODE, loc != null ? loc.lineSpan() : 0)
                .build();
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
                processCallable(md, typeEntity, qn, ctx, EntityKind.METHOD);
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

    private void processCallable(CallableDeclaration<?> callable, Entity owner, String ownerQn,
                                 Context ctx, EntityKind kind) {
        String params = callable.getParameters().stream()
                .map(p -> p.getType().asString())
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
        if (callable instanceof MethodDeclaration md) {
            b.attribute(Entity.Attributes.RETURN_TYPE, md.getType().asString());
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
            ctx.out.relationship(Relationship.builder(RelationshipKind.CALLS,
                            callerId, call.getNameAsString())
                    .resolved(false)
                    .attribute("callName", call.getNameAsString())
                    .attribute("argCount", Integer.toString(call.getArguments().size()))
                    .build());
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
