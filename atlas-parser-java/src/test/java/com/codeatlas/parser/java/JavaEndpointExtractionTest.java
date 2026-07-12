package com.codeatlas.parser.java;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks endpoint, JPA and receiver extraction — the parser half of the lineage slice. */
class JavaEndpointExtractionTest {

    private final JavaLanguageParser parser = new JavaLanguageParser();

    private ParseResult parse(String content) {
        return parser.parse(new ParseRequest("src/S.java", Path.of("src/S.java"), content, "h", "java"));
    }

    @Test
    void composesClassAndMethodPathsIntoTheEndpointIdentity() {
        ParseResult r = parse("""
                package com.example;

                @RestController
                @RequestMapping("/api/customers")
                public class C {
                    @PostMapping("/{id}/orders")
                    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest body,
                                                                @PathVariable Long id,
                                                                @RequestParam String source) {
                        return null;
                    }
                }
                """);
        Entity endpoint = r.entities().stream().filter(e -> e.kind() == EntityKind.ENDPOINT)
                .findFirst().orElseThrow();
        assertEquals("java:endpoint:POST:/api/customers/{id}/orders", endpoint.id());
        assertEquals("POST", endpoint.attribute(Entity.Attributes.HTTP_METHOD).orElseThrow());
        assertEquals("/api/customers/{id}/orders", endpoint.attribute(Entity.Attributes.HTTP_PATH).orElseThrow());
        assertEquals("OrderRequest", endpoint.attribute(Entity.Attributes.REQUEST_BODY_TYPE).orElseThrow());
        assertEquals("OrderResponse", endpoint.attribute(Entity.Attributes.RETURN_TYPE_NORMALIZED).orElseThrow(),
                "ResponseEntity wrapper must be unwrapped");
        assertTrue(endpoint.boolAttribute(Entity.Attributes.VALIDATED, false), "@Valid must be recorded");
        assertEquals("id:path,source:query", endpoint.attribute(Entity.Attributes.HTTP_PARAMS).orElseThrow());
        assertTrue(endpoint.location().isPresent(), "endpoint must carry source evidence");

        // Discovery edges with rule evidence.
        assertTrue(r.relationships().stream().anyMatch(x -> x.kind() == RelationshipKind.EXPOSES
                && "ATLAS-LINEAGE-ENDPOINT-001".equals(x.attributes().get(EvidenceKeys.RULE_ID))));
        assertTrue(r.relationships().stream().anyMatch(x -> x.kind() == RelationshipKind.INVOKES
                && x.toId().equals("java:method:com.example.C#create(OrderRequest,Long,String)")));
    }

    @Test
    void nonLiteralPathStaysExplicitlyUnresolvedInsteadOfGuessed() {
        ParseResult r = parse("""
                package com.example;
                @RestController
                @RequestMapping(Paths.BASE)
                public class C {
                    @GetMapping("/x")
                    public String get() { return ""; }
                }
                """);
        Entity endpoint = r.entities().stream().filter(e -> e.kind() == EntityKind.ENDPOINT)
                .findFirst().orElseThrow();
        assertTrue(endpoint.boolAttribute(Entity.Attributes.HTTP_PATH_UNRESOLVED, false),
                "constant-based path must be marked unresolved");
        assertTrue(endpoint.attribute(Entity.Attributes.HTTP_PATH).orElseThrow().contains("unresolved"),
                "path must carry the unresolved marker, not a guess");
    }

    @Test
    void extractsJpaMappingAndRepositoryManagedType() {
        ParseResult r = parse("""
                package com.example;
                @Entity
                @Table(name = "customer")
                public class CustomerEntity { @Id private Long id; }
                """);
        Entity entity = r.entities().stream().filter(e -> e.name().equals("CustomerEntity"))
                .findFirst().orElseThrow();
        assertTrue(entity.boolAttribute(Entity.Attributes.JPA_ENTITY, false));
        assertEquals("customer", entity.attribute(Entity.Attributes.JPA_TABLE_NAME).orElseThrow());

        ParseResult repo = parse("""
                package com.example;
                public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> { }
                """);
        Entity iface = repo.entities().stream().filter(e -> e.kind() == EntityKind.INTERFACE)
                .findFirst().orElseThrow();
        assertTrue(iface.boolAttribute(Entity.Attributes.SPRING_DATA_REPOSITORY, false));
        assertEquals("CustomerEntity", iface.attribute(Entity.Attributes.MANAGED_ENTITY_TYPE).orElseThrow());
        assertEquals("repository", iface.attribute(Entity.Attributes.ROLE).orElseThrow());
    }

    @Test
    void missingTableAnnotationLeavesNoExplicitTableName() {
        ParseResult r = parse("""
                package com.example;
                @Entity
                public class OrderEntity { @Id private Long id; }
                """);
        Entity entity = r.entities().stream().filter(e -> e.name().equals("OrderEntity"))
                .findFirst().orElseThrow();
        assertTrue(entity.boolAttribute(Entity.Attributes.JPA_ENTITY, false));
        assertTrue(entity.attribute(Entity.Attributes.JPA_TABLE_NAME).isEmpty(),
                "default naming is the analyzer's documented inference, never a parser fact");
    }

    @Test
    void callsCarryReceiverNamesAndEvidenceLocations() {
        ParseResult r = parse("""
                package com.example;
                public class S {
                    private OrderService orderService;
                    void run() {
                        orderService.place(1);
                        this.orderService.cancel(2);
                        helper();
                    }
                    void helper() { }
                }
                """);
        Relationship viaField = call(r, "place");
        assertEquals("orderService", viaField.attributes().get(EvidenceKeys.RECEIVER_NAME));
        assertTrue(viaField.location().isPresent(), "call must carry its source line as evidence");

        assertEquals("orderService", call(r, "cancel").attributes().get(EvidenceKeys.RECEIVER_NAME),
                "this.field receivers must resolve to the field name");
        assertTrue(!call(r, "helper").attributes().containsKey(EvidenceKeys.RECEIVER_NAME),
                "implicit-this calls have no receiver");
    }

    private static Relationship call(ParseResult r, String name) {
        return r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.CALLS
                        && name.equals(x.attributes().get(EvidenceKeys.CALL_NAME)))
                .findFirst().orElseThrow();
    }
}
