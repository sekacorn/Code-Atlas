package com.codeatlas.parser.java;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Deterministic extraction of Spring / JPA lineage evidence from AST annotations.
 *
 * <p>Everything here is annotation- and signature-level: no classpath, no
 * reflection, no framework runtime. Values that are not compile-time string
 * literals are reported as <em>unresolved</em> rather than guessed, per the
 * platform's honesty rules. JAX-RS annotations are not yet supported (see
 * KNOWN_LIMITATIONS.md).
 */
final class SpringLineageExtractor {

    /** HTTP-verb mapping annotations → verbs. {@code @RequestMapping} on a method is not mapped to a verb. */
    private static final Map<String, String> VERB_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "PatchMapping", "PATCH",
            "DeleteMapping", "DELETE");

    private static final Set<String> RESPONSE_WRAPPERS =
            Set.of("ResponseEntity", "Optional", "List", "Set", "Collection", "Iterable");

    private static final Set<String> SPRING_DATA_REPOSITORIES = Set.of(
            "JpaRepository", "CrudRepository", "ListCrudRepository",
            "PagingAndSortingRepository", "ListPagingAndSortingRepository");

    private SpringLineageExtractor() {
    }

    /** A path value that is either a resolved literal or an explicitly unresolved expression. */
    record PathValue(String path, boolean unresolved) {
        static final PathValue EMPTY = new PathValue("", false);
    }

    /** Everything lineage-relevant discovered on a type declaration. */
    record TypeLineage(String role, PathValue basePath, boolean jpaEntity, PathValue tableName,
                       boolean springDataRepository, String managedEntityType) {
    }

    /** One HTTP endpoint discovered on a handler method. */
    record EndpointInfo(String verb, String path, boolean pathUnresolved, String requestBodyType,
                        String returnTypeNormalized, String httpParams, boolean validated) {
    }

    static TypeLineage extractType(TypeDeclaration<?> type) {
        String role = null;
        if (hasAnnotation(type, "RestController") || hasAnnotation(type, "Controller")) {
            role = "controller";
        } else if (hasAnnotation(type, "Service")) {
            role = "service";
        } else if (hasAnnotation(type, "Repository")) {
            role = "repository";
        } else if (hasAnnotation(type, "Mapper")) {
            role = "mapper-interface";
        }

        PathValue basePath = annotationPath(type, "RequestMapping").orElse(PathValue.EMPTY);
        boolean jpaEntity = hasAnnotation(type, "Entity");
        PathValue tableName = annotationMember(type, "Table", "name")
                .map(SpringLineageExtractor::toPathValue).orElse(null);

        boolean springData = false;
        String managedType = null;
        if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid && cid.isInterface()) {
            for (ClassOrInterfaceType ext : cid.getExtendedTypes()) {
                if (SPRING_DATA_REPOSITORIES.contains(ext.getNameAsString())) {
                    springData = true;
                    role = "repository";
                    managedType = ext.getTypeArguments()
                            .filter(args -> !args.isEmpty())
                            .map(args -> args.get(0).asString())
                            .orElse(null);
                    break;
                }
            }
        }
        return new TypeLineage(role, basePath, jpaEntity, tableName, springData, managedType);
    }

    /** Extracts endpoint info from a handler method, or empty when it has no verb annotation. */
    static Optional<EndpointInfo> extractEndpoint(MethodDeclaration method, PathValue basePath) {
        String verb = null;
        PathValue methodPath = PathValue.EMPTY;
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if (VERB_ANNOTATIONS.containsKey(name)) {
                verb = VERB_ANNOTATIONS.get(name);
                methodPath = pathOf(ann);
                break;
            }
        }
        if (verb == null) {
            return Optional.empty();
        }

        boolean unresolved = basePath.unresolved() || methodPath.unresolved();
        String path = unresolved
                ? "{unresolved:" + firstNonBlank(basePath.path(), methodPath.path()) + "}"
                : composePath(basePath.path(), methodPath.path());

        String requestBodyType = null;
        boolean validated = hasAnnotation(method, "Validated");
        StringJoiner params = new StringJoiner(",");
        for (Parameter p : method.getParameters()) {
            if (p.getAnnotationByName("RequestBody").isPresent()) {
                requestBodyType = baseTypeName(p.getType());
            }
            if (p.getAnnotationByName("Valid").isPresent() || p.getAnnotationByName("Validated").isPresent()) {
                validated = true;
            }
            p.getAnnotationByName("PathVariable").ifPresent(a -> params.add(p.getNameAsString() + ":path"));
            p.getAnnotationByName("RequestParam").ifPresent(a -> params.add(p.getNameAsString() + ":query"));
            p.getAnnotationByName("RequestHeader").ifPresent(a -> params.add(p.getNameAsString() + ":header"));
        }

        return Optional.of(new EndpointInfo(verb, path, unresolved, requestBodyType,
                normalizeReturnType(method.getType()), params.length() > 0 ? params.toString() : null, validated));
    }

    /**
     * Unwraps well-known response wrappers so {@code ResponseEntity<List<CustomerResponse>>}
     * normalizes to {@code CustomerResponse}. Runtime serialization is out of scope.
     */
    static String normalizeReturnType(Type type) {
        Type current = type;
        while (current instanceof ClassOrInterfaceType cit
                && RESPONSE_WRAPPERS.contains(cit.getNameAsString())) {
            var args = cit.getTypeArguments().orElse(null);
            if (args == null || args.size() != 1) {
                break;
            }
            current = args.get(0);
        }
        return baseTypeName(current);
    }

    /** The bare type name: generics, arrays and scopes stripped, whitespace removed. */
    static String baseTypeName(Type type) {
        String text = type.asString().replaceAll("\\s+", "");
        int lt = text.indexOf('<');
        if (lt >= 0) {
            text = text.substring(0, lt);
        }
        text = text.replace("[]", "").replace("...", "");
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1) : text;
    }

    // ---- annotation plumbing ----

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotationByName(name).isPresent();
    }

    private static Optional<PathValue> annotationPath(NodeWithAnnotations<?> node, String name) {
        return node.getAnnotationByName(name).map(SpringLineageExtractor::pathOf);
    }

    /** Reads the {@code value}/{@code path} member of a mapping annotation. */
    private static PathValue pathOf(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return toPathValue(single.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                    return toPathValue(pair.getValue());
                }
            }
        }
        return PathValue.EMPTY; // marker annotation: no path member
    }

    private static Optional<Expression> annotationMember(NodeWithAnnotations<?> node, String annotation,
                                                         String member) {
        Optional<AnnotationExpr> ann = node.getAnnotationByName(annotation);
        if (ann.isEmpty()) {
            return Optional.empty();
        }
        if (ann.get() instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(member)) {
                    return Optional.of(pair.getValue());
                }
            }
        }
        return Optional.empty();
    }

    /** A string literal resolves; any other expression (constant ref, concat) stays unresolved. */
    private static PathValue toPathValue(Expression expr) {
        if (expr instanceof StringLiteralExpr literal) {
            return new PathValue(literal.getValue(), false);
        }
        return new PathValue(expr.toString(), true);
    }

    /** Joins base and method paths with single slashes: "/api/" + "x" -> "/api/x". */
    static String composePath(String base, String method) {
        String joined = ("/" + base + "/" + method).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** Ordered attribute map for an endpoint entity (deterministic rendering). */
    static Map<String, String> endpointAttributes(EndpointInfo e, String controllerQn, String handlerQn) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("controller", controllerQn);
        attrs.put("handler", handlerQn);
        if (e.requestBodyType() != null) {
            attrs.put("requestBodyType", e.requestBodyType());
        }
        if (e.returnTypeNormalized() != null && !e.returnTypeNormalized().equals("void")) {
            attrs.put("returnTypeNormalized", e.returnTypeNormalized());
        }
        if (e.httpParams() != null) {
            attrs.put("httpParams", e.httpParams());
        }
        return attrs;
    }
}
