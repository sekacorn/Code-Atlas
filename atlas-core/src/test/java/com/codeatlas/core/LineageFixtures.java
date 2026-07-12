package com.codeatlas.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A representative Spring-style customer application used by the lineage tests.
 *
 * <p>Contains one complete, resolvable path (POST /customers → controller →
 * service → mapper → repository.save → customer table → response DTO) plus two
 * deliberate honesty cases: an interface with <em>two</em> implementations
 * (ambiguous DI) and a call through a field whose type is not in the repository
 * (unresolvable external client).
 */
final class LineageFixtures {

    private LineageFixtures() {
    }

    static void writeCustomerApp(Path repo) throws IOException {
        Path pkg = repo.resolve("src/main/java/com/example/customer");
        Files.createDirectories(pkg);

        Files.writeString(pkg.resolve("CustomerController.java"), """
                package com.example.customer;

                @RestController
                @RequestMapping("/customers")
                public class CustomerController {

                    @Autowired
                    private CustomerService customerService;

                    @PostMapping
                    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CustomerRequest request) {
                        return ResponseEntity.ok(customerService.createCustomer(request));
                    }

                    @GetMapping("/{id}")
                    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable Long id) {
                        return ResponseEntity.ok(customerService.getCustomer(id));
                    }
                }
                """);

        Files.writeString(pkg.resolve("CustomerService.java"), """
                package com.example.customer;

                @Service
                public class CustomerService {

                    @Autowired
                    private CustomerRepository customerRepository;
                    @Autowired
                    private CustomerMapper customerMapper;
                    @Autowired
                    private CustomerValidator customerValidator;
                    @Autowired
                    private NotificationSender notificationSender;
                    @Autowired
                    private AnalyticsClient analyticsClient;

                    public CustomerResponse createCustomer(CustomerRequest request) {
                        customerValidator.validate(request);
                        CustomerEntity entity = customerMapper.toEntity(request);
                        customerRepository.save(entity);
                        notificationSender.send(entity);
                        analyticsClient.push(entity);
                        return customerMapper.toResponse(entity);
                    }

                    public CustomerResponse getCustomer(Long id) {
                        CustomerEntity entity = customerRepository.findById(id);
                        return customerMapper.toResponse(entity);
                    }
                }
                """);

        Files.writeString(pkg.resolve("CustomerMapper.java"), """
                package com.example.customer;

                public class CustomerMapper {

                    CustomerEntity toEntity(CustomerRequest request) {
                        CustomerEntity entity = new CustomerEntity();
                        return entity;
                    }

                    CustomerResponse toResponse(CustomerEntity entity) {
                        CustomerResponse response = new CustomerResponse();
                        return response;
                    }
                }
                """);

        Files.writeString(pkg.resolve("CustomerValidator.java"), """
                package com.example.customer;

                public class CustomerValidator {

                    void validate(CustomerRequest request) {
                    }
                }
                """);

        Files.writeString(pkg.resolve("CustomerRepository.java"), """
                package com.example.customer;

                public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
                }
                """);

        Files.writeString(pkg.resolve("CustomerEntity.java"), """
                package com.example.customer;

                @Entity
                @Table(name = "customer")
                public class CustomerEntity {
                    @Id
                    private Long id;
                    @Column
                    private String name;
                }
                """);

        Files.writeString(pkg.resolve("CustomerRequest.java"), """
                package com.example.customer;

                public class CustomerRequest {
                    private String name;
                }
                """);

        Files.writeString(pkg.resolve("CustomerResponse.java"), """
                package com.example.customer;

                public class CustomerResponse {
                    private String name;
                }
                """);

        Files.writeString(pkg.resolve("NotificationSender.java"), """
                package com.example.customer;

                public interface NotificationSender {
                    void send(CustomerEntity entity);
                }
                """);

        Files.writeString(pkg.resolve("EmailNotificationSender.java"), """
                package com.example.customer;

                public class EmailNotificationSender implements NotificationSender {
                    public void send(CustomerEntity entity) {
                    }
                }
                """);

        Files.writeString(pkg.resolve("SmsNotificationSender.java"), """
                package com.example.customer;

                public class SmsNotificationSender implements NotificationSender {
                    public void send(CustomerEntity entity) {
                    }
                }
                """);
        // Note: AnalyticsClient is deliberately NOT in the repository — the service's
        // analyticsClient.push(...) call must surface as an unresolved lineage gap.
    }
}
