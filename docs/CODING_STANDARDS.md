# Coding Standards — Marketplace Backend (app-java-v3)

> **Status:** Living document • Last updated: 2026-06-20
> **Audience:** All developers (including AI agents) writing code in this repository
> **Scope:** Mandatory conventions for writing Java code in the 16 marketplace modules
>
> **How to use:** Before writing any new class or modifying an existing one, check the relevant section here. Every convention is backed by either an official Spring/Java reference or an existing pattern already established in the codebase.

---

## Table of Contents

1. [Project Layout & Module Boundaries](#1-project-layout--module-boundaries)
2. [Dependency Injection](#2-dependency-injection)
3. [Transactions](#3-transactions)
4. [Events](#4-events)
5. [JPA Entities & Repositories](#5-jpa-entities--repositories)
6. [REST Controllers & API Design](#6-rest-controllers--api-design)
7. [Service Layer](#7-service-layer)
8. [Error Handling](#8-error-handling)
9. [Caching](#9-caching)
10. [Observability](#10-observability)
11. [Security](#11-security)
12. [Logging](#12-logging)
13. [Testing](#13-testing)
14. [Naming Conventions](#14-naming-conventions)
15. [Null Handling](#15-null-handling)
16. [Maven & Build](#16-maven--build)
17. [Deployment](#17-deployment)
18. [Spring Security 7.x Compliance](#18-spring-security-7x-compliance)
19. [Spring Cloud Gateway (when to use)](#19-spring-cloud-gateway-when-to-use)

---

## 1. Project Layout & Module Boundaries

### 1.1 Layered Structure

```
marketplace-app              ← Composition root: @SpringBootApplication, admin REST, wiring
marketplace-platform-infra   ← Cross-cutting: JPA base, Security, Cache, Observability, Email
marketplace-shared           ← API contracts: Port interfaces, events, exceptions, DTOs (NO implementation)
marketplace-<domain>         ← 13 domain modules: own their JPA entities, repos, services, controllers
```

### 1.2 Module Boundary Rules (verified by ModulithVerificationTest)

✅ **DO:**
- Domain modules may depend on `marketplace-shared` named interfaces only: `shared-api`, `shared-security`, `shared-jpa`, `shared-config`
- Cross-module communication via SPIs (in `marketplace-shared/api/`) or via events
- Declare `@ApplicationModule(allowedDependencies = {...})` in each `package-info.java`

❌ **DON'T:**
- Domain modules depending on each other directly (e.g., `marketplace-booking` → `marketplace-catalog`)
- Putting business logic in `marketplace-shared` (it's contracts only)
- Putting security config in domain modules (belongs in `marketplace-platform-infra`)

**Example `package-info.java`:**
```java
@org.springframework.modulith.NamedInterface("booking")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.booking;
```

**Reference:** [Spring Modulith — Application Modules](https://docs.spring.io/spring-modulith/reference/application-modules.html)

---

## 2. Dependency Injection

### 2.1 Constructor Injection (mandatory)

✅ **DO:**
```java
@Service
@Transactional
public class BookingService {
    private final BookingRepository bookingRepository;
    private final CurrentUserProvider currentUserProvider;

    public BookingService(BookingRepository bookingRepository,
                          CurrentUserProvider currentUserProvider) {
        this.bookingRepository = bookingRepository;
        this.currentUserProvider = currentUserProvider;
    }
}
```

❌ **DON'T:**
```java
@Service
public class BookingService {
    @Autowired  // ❌ field injection
    private BookingRepository bookingRepository;

    @Autowired  // ❌ setter injection
    public void setBookingRepository(BookingRepository repo) { ... }
}
```

### 2.2 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Use **constructor injection** for all required dependencies | Immutable, testable, fails fast | [Spring Framework — Constructor Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-injection) |
| Make fields `final` | Enforces immutability | Java best practice |
| Omit `@Autowired` on constructors with single dependency | Spring 4.3+ auto-detects | Spring docs |
| Use `@Lazy` only for circular dependencies (e.g., self-injection) | Document why in Javadoc | [Spring — Bean Lazy Initialization](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-factory-lazy-init) |
| Use `@Qualifier` when multiple beans of same type exist | Disambiguates | Spring docs |

---

## 3. Transactions

### 3.1 Service-Level Transactions

✅ **DO:**
```java
@Service
@Transactional  // class-level default = read-write
public class BookingService {

    @Transactional(readOnly = true)  // override for queries
    public Booking getById(UUID id) { ... }

    @Transactional  // explicit read-write for mutations
    public Booking create(BookingRequest req) { ... }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByBooking(UUID bookingId) { ... }
}
```

❌ **DON'T:**
```java
@RestController
@Transactional  // ❌ on controller
public class BookingController { ... }

@Service
public class BookingService {
    @Transactional
    public void process() {
        // ❌ calling private method — proxy doesn't apply @Transactional
        helperMethod();
    }

    @Transactional  // ❌ private — proxy can't intercept
    private void helperMethod() { ... }
}
```

### 3.2 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| `@Transactional` on **service** methods, not controllers | Service is the business boundary | [Spring — Declarative Transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative.html) |
| `@Transactional(readOnly = true)` for all query methods | Hibernate skips dirty checking → faster | Spring docs |
| `@Transactional(propagation = REQUIRES_NEW)` for independent sub-transactions | Survives caller rollback | Spring docs |
| `@Transactional` does **not** work on `private` methods | Spring uses CGLIB proxies — only public methods | [Spring — AOP Proxying](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html) |
| `@Transactional` does **not** work on self-invocation | Use self-injection (`@Lazy self`) for internal calls | [Spring — Self-Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html) |

---

## 4. Events

### 4.1 Event Records

✅ **DO:**
```java
// In marketplace-shared/api/
public record BookingCreatedEvent(UUID bookingId) {}
public record BookingConfirmedEvent(UUID bookingId) {}
public record BookingCancelledEvent(UUID bookingId, String reason) {}
```

### 4.2 Publishing Events

✅ **DO:**
```java
@Service
public class BookingService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Booking confirm(UUID id) {
        Booking booking = ...;
        eventPublisher.publishEvent(new BookingConfirmedEvent(booking.getId()));
        return booking;
    }
}
```

### 4.3 Listening to Events

✅ **DO:**
```java
@ApplicationModuleListener  // cross-module, transactional, retryable
public void onBookingConfirmed(BookingConfirmedEvent event) { ... }

@TransactionalEventListener  // same-module, async after commit
public void onPaymentStateChanged(PaymentStateChangedEvent event) { ... }
```

❌ **DON'T:**
```java
@ApplicationModuleListener
public void onBookingConfirmed(BookingConfirmedEvent event) {
    try {
        // ❌ catch(Exception) defeats retry — event marked COMPLETED
    } catch (Exception e) {
        log.error("Failed", e);
    }
}
```

### 4.4 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Events are **records** (immutable, concise) | Java 16+ best practice | [Java Records](https://docs.oracle.com/en/java/javase/25/language/records.html) |
| Event names are **past tense** (`BookingCreated`, not `BookingCreate`) | Describes what happened | Domain-Driven Design |
| Events live in `marketplace-shared/api/` | Available to all modules | Modulith pattern |
| `@ApplicationModuleListener` for **cross-module** events | Module-scoped, transactional, retryable | [Spring Modulith — Events](https://docs.spring.io/spring-modulith/reference/events.html) |
| `@TransactionalEventListener` for **same-module** events | Fine-grained control | Spring docs |
| **Never** `catch (Exception)` in event listeners | Defeats Event Publication Log retry | Spring Modulith docs |
| **Catch `DataAccessException` only** for "best-effort per item" patterns | Lets programming errors propagate for retry | [Spring — DataAccessException](https://docs.spring.io/spring-framework/reference/data-access/dao.html) |

---

## 5. JPA Entities & Repositories

### 5.1 Entity Pattern

✅ **DO:**
```java
@Entity
@Table(name = "bookings")
@Audited  // Hibernate Envers for revision history
public class Booking extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;  // monetary value in minor units

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "SAR";

    protected Booking() {}  // JPA requires no-arg constructor

    public Booking(UUID id, UUID consumerId, ...) {  // factory-style constructor
        this.id = id;
        // ...
    }

    // Business methods that enforce invariants
    public void markConfirmed() {
        if (status != BookingStatus.PENDING) {
            throw new IllegalStateException("Cannot confirm: " + status);
        }
        this.status = BookingStatus.CONFIRMED;
    }
}
```

### 5.2 Repository Pattern

✅ **DO:**
```java
public interface BookingRepository extends
        JpaRepository<Booking, UUID>,
        JpaSpecificationExecutor<Booking>,
        RevisionRepository<Booking, UUID, Integer> {

    Page<Booking> findByConsumerId(UUID consumerId, Pageable pageable);
    Optional<Booking> findByIdempotencyKey(String key);
}
```

### 5.3 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| All entities extend `BaseEntity` | Provides `@Version` (optimistic locking), `@CreatedBy/createdDate`, `@SoftDelete` | [Hibernate — Soft Delete](https://docs.jboss.org/hibernate/orm/7/user-guide/html_single/Hibernate_User_Guide.html#soft-delete) |
| Use `UUID` for primary keys | Globally unique, no sequence contention | [UUID](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/UUID.html) |
| Money stored as `Long` (cents/minor units) | Avoids `BigDecimal` rounding issues | [Martin Fowler — Money Pattern](https://martinfowler.com/eaaCatalog/money.html) |
| `@Column(name = "snake_case")` on every column | PostgreSQL convention | SQL standard |
| `@Enumerated(EnumType.STRING)` for enums | Readable in DB, survives reordering | Hibernate docs |
| `protected` no-arg constructor for JPA | JPA requirement, prevents accidental use | Hibernate docs |
| Public constructor for **business creation** (factory-style) | Encapsulates invariants | DDD |
| **Business methods** on entities (`markConfirmed()`) instead of setters | Enforces invariants | DDD |
| Repositories extend `JpaRepository<T, UUID>` | Standard CRUD + paging | [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/) |
| Add `JpaSpecificationExecutor` for dynamic queries | Composable predicates | Spring Data docs |
| Add `RevisionRepository` for audited entities | Hibernate Envers integration | [Hibernate Envers](https://docs.jboss.org/envers/) |
| Derive query methods from method names (`findByConsumerId`) | No `@Query` needed for simple cases | Spring Data JPA docs |
| Use `@Query` only for complex joins or native SQL | Document why | Spring Data docs |

---

## 6. REST Controllers & API Design

### 6.1 Controller Pattern

✅ **DO:**
```java
@RestController
@RequestMapping(value = ApiConstants.BOOKING, version = "1.0")
public class BookingController {

    private final BookingService bookingService;
    private final BookingMapper bookingMapper;

    public BookingController(BookingService bookingService, BookingMapper bookingMapper) { ... }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(bookingMapper.toResponse(bookingService.getByIdForUser(id, auth)));
    }

    @PostMapping
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                   Authentication auth) {
        Booking booking = bookingService.create(request, auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingMapper.toResponse(booking));
    }
}
```

### 6.2 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| `@RestController` + `@RequestMapping(value = ApiConstants.X, version = "1.0")` | Consistent API paths + versioning | [Spring — API Versioning](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-requestmapping-version.html) |
| API paths defined in `ApiConstants` (single source of truth) | Prevents drift | Existing pattern |
| `@PreAuthorize` for role-based access | Spring Security integration | [Spring Security — PreAuthorize](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html) |
| `@Valid @RequestBody` for request validation | Bean Validation | [Jakarta Validation](https://jakarta.ee/specifications/bean-validation/3.0/) |
| Return `ResponseEntity<T>` with explicit status codes | Type-safe, explicit | Spring docs |
| Use `@ResponseStatus(HttpStatus.CREATED)` for POST | RESTful | Spring docs |
| Use **MapStruct** mappers (`BookingMapper`) for entity → DTO | Compile-time, no reflection | [MapStruct](https://mapstruct.org/) |
| `Authentication` parameter for current user context | Spring Security pattern | Spring docs |
| Use `Pageable` for list endpoints | Standard pagination | [Spring Data — Pageable](https://docs.spring.io/spring-data/commons/reference/repositories/core-concepts.html) |
| Wrap paged responses in `PagedResponse<T>` | Consistent envelope | Existing pattern |
| `@RateLimiter(name = "...")` on heavy endpoints | Resilience4j | [Resilience4j — RateLimiter](https://resilience4j.readme.io/docs/ratelimiter) |

### 6.3 API Path Constants

```java
public final class ApiConstants {
    public static final String API_V1 = "/api/v1";
    public static final String IDENTITY = API_V1 + "/users";
    public static final String CATALOG = API_V1 + "/listings";
    public static final String BOOKING = API_V1 + "/bookings";
    // ...
    private ApiConstants() {}  // prevent instantiation
}
```

---

## 7. Service Layer

### 7.1 Service Pattern

✅ **DO:**
```java
@Service
@Transactional
@Validated  // for @Min, @NotNull on method params
public class PaymentsService implements PaymentsSpi {  // implements SPI for cross-module access

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);

    @Observed(name = "payment.process")  // Micrometer observation
    @CacheEvict(cacheNames = "payments", key = "#result.id")
    public Payment process(@NotNull UUID bookingId) { ... }
}
```

### 7.2 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| `@Service` on all service classes | Spring stereotype | Spring docs |
| `@Transactional` at class level (read-write default) | Consistent | Spring docs |
| `@Validated` for parameter validation | Method-level validation | [Spring — Method Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html#validation-method) |
| Implement SPI interface (e.g., `PaymentsSpi`) if other modules need access | Cross-module contract | Modulith pattern |
| Use `@Observed` for Micrometer metrics | Observability | [Micrometer — Observation](https://micrometer.io/docs/observation) |
| `private static final Logger log = LoggerFactory.getLogger(...)` | SLF4J | [SLF4J](http://www.slf4j.org/) |
| Service returns **entities** (controller maps to DTO) | Separation of concerns | Layered architecture |
| Service **does not** return `ResponseEntity` | That's the controller's job | Layered architecture |

---

## 8. Error Handling

### 8.1 Exception Hierarchy

```
ApiProblemDetailException (base, implements RFC 7807 ProblemDetail)
├── BadRequestException        → 400
├── ConflictException          → 409
├── ResourceNotFoundException  → 404
```

### 8.2 Throwing Exceptions

✅ **DO:**
```java
public Booking getById(UUID id) {
    return bookingRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));
}

public void cancel(UUID id, Authentication auth) {
    if (!isOwner(id, auth)) {
        throw new AccessDeniedException("Not your booking");
    }
}
```

❌ **DON'T:**
```java
throw new RuntimeException("Booking not found");  // ❌ too generic
throw new IllegalArgumentException("...");         // ❌ use BadRequestException
return null;                                       // ❌ use Optional or throw
```

### 8.3 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Use the project's exception hierarchy | Consistent RFC 7807 responses | [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) |
| `ResourceNotFoundException` for missing resources | → 404 | Existing pattern |
| `BadRequestException` for invalid input | → 400 | Existing pattern |
| `ConflictException` for state conflicts | → 409 | Existing pattern |
| `AccessDeniedException` for authorization failures | → 403 | Spring Security |
| **Never** return `null` for optional values | Use `Optional<T>` | [Optional](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html) |
| **Never** `catch (Exception)` broadly | Narrow to specific exceptions | Clean code |
| **Never** swallow exceptions silently | Log at minimum, rethrow if unsure | OWASP |

---

## 9. Caching

✅ **DO:**
```java
@Cacheable(cacheNames = "bookings", key = "#id")
public Booking getById(UUID id) { ... }

@CacheEvict(cacheNames = "bookings", key = "#id")
public Booking update(UUID id, UpdateRequest req) { ... }

@CacheEvict(cacheNames = "bookings", allEntries = true)
public void bulkUpdate() { ... }
```

### Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| `@Cacheable` on read-heavy, rarely-changing data | Performance | [Spring — Cache Abstraction](https://docs.spring.io/spring-framework/reference/integration/cache.html) |
| `@CacheEvict` on every mutation method | Prevents stale data | Spring docs |
| Use explicit `key` SpEL (not default) | Predictable cache behavior | Spring docs |
| Cache names match domain (`"bookings"`, `"availability"`) | Discoverable | Existing pattern |
| **Caches holding JDK-serialized records (`ListingSummary`) MUST version their names (`-v2`) — bump with every record-component change, in the annotations, the invalidation set AND the yml list together** | A record-component change makes pre-change entries deserialize with default-value components (null currency) and serve them as hits; the version bump is the deploy-time eviction | Java Object Serialization Spec — Record Serialization; guarded by `ListingSummaryCacheContractFilesTest` (CodeRabbit #241) |
| Redis backing configured in `application.yml` (`spring.cache.type=redis`) | Distributed cache | [Spring Boot — Redis Cache](https://docs.spring.io/spring-boot/reference/io/caching.html#io.caching.provider.redis) |

---

## 10. Observability

✅ **DO:**
```java
@Observed(name = "booking.create")  // Micrometer observation
public Booking create(...) { ... }
```

### Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| `@Observed` on all public service methods that represent business operations | Metrics + tracing | [Micrometer — Observation](https://micrometer.io/docs/observation) |
| Observation name format: `<module>.<operation>` (e.g., `booking.create`) | Discoverable in dashboards | Existing pattern |
| Actuator endpoints exposed: `health`, `info`, `prometheus`, `metrics` | Standard | [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html) |
| `logback-spring.xml` for redaction (passwords, tokens, JWTs) | Security | OWASP Logging |

---

## 11. Security

### 11.1 Method-Level Authorization

✅ **DO:**
```java
@PreAuthorize("hasRole('CONSUMER')")
public ResponseEntity<BookingResponse> create(...) { ... }

@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<UserResponse> suspend(UUID id) { ... }
```

### 11.2 Current User

✅ **DO:**
```java
@Autowired
private CurrentUserProvider currentUserProvider;

UUID userId = currentUserProvider.getCurrentUserId(authentication);
```

### 11.3 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| `@PreAuthorize` on every mutating endpoint | Defense in depth | [Spring Security — Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html) |
| Never trust client-sent user IDs | Use `CurrentUserProvider` | OWASP |
| Password max length = 64 (NIST SP 800-63B) | Prevents DoS | [NIST SP 800-63B §5.1.1](https://pages.nist.gov/800-63-3/sp800-63b.html) |
| TOTP comparison via `MessageDigest.isEqual` (constant-time) | Timing-attack resistant | [RFC 6238 §5.2](https://datatracker.ietf.org/doc/html/rfc6238#section-5.2) |
| JWT in `HttpOnly + Secure + SameSite=Strict` cookie | Prevents XSS theft | [RFC 6749 §10.6](https://datatracker.ietf.org/doc/html/rfc6749#section-10.6) |
| Never log secrets (passwords, tokens, JWTs) | logback-spring.xml redaction | OWASP Logging |
| `server.forward-headers-strategy=framework` in prod | Trusted proxy handling | [Spring Boot — Forwarded Headers](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.forwarded-headers) |

---

## 12. Logging

✅ **DO:**
```java
private static final Logger log = LoggerFactory.getLogger(BookingService.class);

log.info("Booking created: id={}, consumerId={}", booking.getId(), consumerId);
log.warn("Failed login attempt: username={}, attempts={}/{}", username, attempts, max);
log.error("Database error", exception);
```

❌ **DON'T:**
```java
System.out.println("debug");  // ❌ use logger
e.printStackTrace();          // ❌ use log.error("msg", e)
log.info("Booking: " + booking);  // ❌ string concatenation — use {}
log.info("password=" + password);  // ❌ secret in log
```

### Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| SLF4J `LoggerFactory.getLogger(ClassName.class)` | Standard | [SLF4J](http://www.slf4j.org/) |
| Use `{}` placeholders (not string concatenation) | Performance (lazy eval) | SLF4J docs |
| `private static final Logger log` | One per class | Convention |
| Log levels: `ERROR` (failures), `WARN` (recoverable), `INFO` (business events), `DEBUG` (diagnostics) | Standard | Convention |
| **Never** log secrets | Security | OWASP |
| **Never** `System.out` / `System.err` | Use logger | Convention |
| **Never** `e.printStackTrace()` | Use `log.error("msg", e)` | Convention |
| Logback redaction filter strips secrets automatically | Defense in depth | `logback-spring.xml` |

---

## 13. Testing

### 13.1 Test Structure

✅ **DO:**
```java
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, eventPublisher, ...);
    }

    @Test
    void create_setsStatusToPending() {
        // given
        UUID consumerId = Instancio.create(UUID.class);
        // when
        Booking result = service.create(...);
        // then
        assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING);
        verify(eventPublisher).publishEvent(any(BookingCreatedEvent.class));
    }
}
```

### 13.2 Test Naming

| Pattern | Example |
|---------|---------|
| `methodName_condition_expected` | `create_setsStatusToPending` |
| `methodName_whenCondition_expected` | `cancel_whenNotOwner_throwsAccessDenied` |
| `methodName_edgeCase_expected` | `createBooking_withPastDate_throwsBadRequest` |

### 13.3 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Unit tests: `*Test.java` (run by Surefire) | Maven convention | [Surefire](https://maven.apache.org/surefire/) |
| Integration tests: `*IT.java` or `*IntegrationTest.java` (run by Failsafe) | Maven convention | [Failsafe](https://maven.apache.org/failsafe/) |
| Use **Mockito** for unit tests (mock dependencies) | Fast, isolated | [Mockito](https://site.mockito.org/) |
| Use **Instancio** for test data generation (`Instancio.create(Type.class)`) | Reduces boilerplate | [Instancio](https://www.instancio.org/) |
| Use **AssertJ** for assertions (`assertThat(...)`) | Fluent, readable | [AssertJ](https://assertj.github.io/doc/) |
| `@ExtendWith(MockitoExtension.class)` for Mockito integration | JUnit 5 | Mockito docs |
| `@BeforeEach` for setup | JUnit 5 | [JUnit 5](https://junit.org/junit5/) |
| `@ApplicationModuleTest` for Modulith integration tests | Module-scoped context | [Spring Modulith — Testing](https://docs.spring.io/spring-modulith/reference/testing.html) |
| `@Testcontainers(disabledWithoutDocker = true)` for DB/Redis integration tests | Graceful skip | [Testcontainers](https://www.testcontainers.org/) |
| JaCoCo coverage ≥ 70% enforced | Quality gate | `jacoco.coverage.threshold=0.70` |
| **Never** `ReflectionTestUtils.setField` for non-final fields | Use constructor injection | [Spring — Testing](https://docs.spring.io/spring-framework/reference/testing/) |
| **Never** `setAccessible(true)` on private methods | Make method package-private instead | Java best practice |
| **Never** `@SuppressWarnings("unchecked")` to silence warnings | Fix the root cause | Clean code |

---

## 14. Naming Conventions

### 14.1 Classes

| Type | Pattern | Example |
|------|---------|---------|
| Entity | Singular noun | `Booking`, `ProviderListing` |
| Repository | `<Entity>Repository` | `BookingRepository` |
| Service | `<Domain>Service` | `BookingService`, `PaymentsService` |
| Controller | `<Domain>Controller` | `BookingController` |
| Event | `<Action>PastTense` + `Event` | `BookingCreatedEvent`, `BookingConfirmedEvent` |
| Exception | `<Problem>Exception` | `ResourceNotFoundException`, `BadRequestException` |
| DTO (request) | `<Action>Request` | `CreateBookingRequest` |
| DTO (response) | `<Entity>Response` | `BookingResponse` |
| DTO (summary) | `<Entity>Summary` | `BookingSummary`, `UserSummary` |
| Mapper | `<Entity>Mapper` | `BookingMapper` |
| Config | `<Domain>Config` | `SecurityConfig`, `OpenApiConfig` |
| Port (SPI) | `<Domain>Port` or `<Domain>Spi` | `AvailabilityPort`, `CatalogSpi` |
| Listener | `<Action>EventListener` or `on<Event>` | `NotificationEventListener`, `onBookingCreated` |

### 14.2 Methods

| Type | Pattern | Example |
|------|---------|---------|
| Query | `findById`, `getById`, `listByX` | `findByConsumerId` |
| Command | `create`, `update`, `delete`, `cancel`, `confirm` | `confirmBooking` |
| Boolean query | `isX`, `hasX`, `canX` | `isLocked`, `hasRole` |
| Event handler | `on<Event>` | `onBookingCreated`, `onDayHasPassed` |
| Factory | `create`, `open`, `of` | `AvailabilitySlot.open(...)` |

### 14.3 Variables

| Type | Pattern | Example |
|------|---------|---------|
| IDs | `<entity>Id` | `bookingId`, `consumerId` |
| Collections | plural | `bookings`, `consumers` |
| Booleans | `is/has/can` prefix | `isAvailable`, `hasPermission` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_FAILED_ATTEMPTS`, `SLOT_GENERATION_DAYS_AHEAD` |

### 14.4 Packages

| Layer | Package | Example |
|-------|---------|---------|
| Main | `com.marketplace.<module>` | `com.marketplace.booking` |
| SPI | `com.marketplace.<module>.spi` | `com.marketplace.identity.spi` |
| Internal | `com.marketplace.<module>.internal` | `com.marketplace.identity.internal` |
| Config | `com.marketplace.<module>.config` | `com.marketplace.config` |

---

## 15. Null Handling

✅ **DO:**
```java
// Return Optional for queries that may not find
public Optional<Booking> findByIdempotencyKey(String key) { ... }

// Throw for "should not happen" cases
public Booking getById(UUID id) {
    return bookingRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));
}

// Use @NotNull / @Nullable annotations for clarity
@NotNull
public Booking create(@NotNull CreateBookingRequest request) { ... }
```

❌ **DON'T:**
```java
public Booking findById(UUID id) {
    return null;  // ❌ use Optional or throw
}

if (booking != null) { ... }  // ❌ use Optional.map/filter/ifPresent
```

### Rules

| Rule | Rationale |
|------|-----------|
| Return `Optional<T>` for "may not exist" queries | Forces caller to handle absence |
| Throw `ResourceNotFoundException` for "must exist" cases | Fail fast |
| Use `@NotNull` / `@Nullable` (Jakarta Validation or JetBrains) | Documents intent |
| **Never** return `null` for collections — use empty | Prevents NPE |
| **Never** pass `null` as method argument | Use overloaded methods or Optional |

---

## 16. Maven & Build

### 16.1 Build Commands

```bash
# Full verification (compile + unit tests + integration tests + JaCoCo + Enforcer)
./mvnw verify

# Quick compile check
./mvnw compile -pl marketplace-booking -am

# Run tests for one module
./mvnw test -pl marketplace-identity

# Skip integration tests (faster feedback)
./mvnw verify -DskipITs
```

### 16.2 Quality Gates (enforced in `verify` phase)

| Gate | Tool | Threshold |
|------|------|-----------|
| Compilation warnings | `maven-compiler-plugin` | `failOnWarning=true` (zero warnings) |
| Test coverage | `jacoco-maven-plugin` | ≥ 70% on all modules |
| Dependency convergence | `maven-enforcer-plugin` | No version conflicts |
| Upper-bound deps | `maven-enforcer-plugin` | No lower-version overrides |
| Duplicate POM deps | `maven-enforcer-plugin` | None |
| Maven version | `maven-enforcer-plugin` | ≥ 3.9 |
| Java version | `maven-enforcer-plugin` | ≥ 21 (target 25) |
| Modulith boundaries | `ModulithVerificationTest` | All modules verified |
| Secret scanning | `gitleaks` (CI) | High+ severity fails |

**Reference:** [Maven — Build Lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)

### 16.3 Adding a New Module

1. Create directory: `marketplace-<name>/`
2. Create `pom.xml` (copy from existing module, change `artifactId`)
3. Add `<module>marketplace-<name></module>` to parent `pom.xml`
4. Create `src/main/java/com/marketplace/<name>/package-info.java`:
   ```java
   @org.springframework.modulith.NamedInterface("<name>")
   @org.springframework.modulith.ApplicationModule(
       allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
   )
   package com.marketplace.<name>;
   ```
5. Create `src/main/resources/db/migration/V<N>__<name>.sql` (Flyway migration)
6. Create entities, repositories, services, controllers following these standards
7. Add tests (unit + integration) — JaCoCo ≥ 70%
8. Run `./mvnw verify` — must pass all gates

### 16.4 Flyway Migration Rules (index edition — CodeRabbit #241 guidance)

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Never modify an applied migration — new change = new `V<N>` file | Flyway checksums / production history | Flyway docs |
| Plain `CREATE INDEX` is the default for this repo's tables | Tables are small; Flyway runs at boot BEFORE the app serves traffic (a failed migration fails startup — the liveness gate), so no live writes are blocked | [PostgreSQL — CREATE INDEX](https://www.postgresql.org/docs/current/sql-createindex.html) |
| For a future index on a LARGE table, use `CREATE INDEX CONCURRENTLY` — which **requires the non-transactional Flyway path**: the migration needs `executeInTransaction=false` AND `flyway.postgresql.transactional.lock=false` (session-level locks; the default transactional advisory lock deadlocks against the concurrent build's wait) — both per the official Flyway transaction-handling docs | `CREATE INDEX` takes a SHARE lock that blocks writes for the whole build; CONCURRENTLY avoids it but refuses to run in a transaction block AND deadlocks against Flyway's default lock | [Flyway — Migration transaction handling](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-transaction-handling) |
| Destructive `DROP TABLE` migrations must ship in a release AFTER the code that stopped using the tables is live (two-release rollout) | A rolling deploy can still run an old replica against the dropped tables | Squawk `ban-drop-table` |
| Unique partial indexes (`WHERE col IS NOT NULL`) for one-to-one links with optional columns | NULL stays free for unlinked rows while linked values are enforced unique | PostgreSQL docs |

---

## 17. Deployment

### 17.1 Spring Boot Deployment Principles

> **Official doc** — [Spring Boot — Deploying Spring Boot Applications](https://docs.spring.io/spring-boot/how-to/deployment/index.html):
> "Spring Boot's flexible packaging options provide a great deal of choice when it comes to deploying your application. You can deploy Spring Boot applications to a variety of cloud platforms and to virtual or real machines."

The deployment options are:
- **AOT Cache** (Java 25+) — for faster startup on the JVM
- **Traditional Deployment** — WAR on servlet containers
- **Container Images** — Dockerfile-based (project uses this)

### 17.2 Dockerfile Conventions

✅ **DO:**
```dockerfile
# Multi-stage build (build → extract layers → runtime)
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am

# Extract layers (Spring Boot 4.1 — use jarmode=tools, NOT layertools)
FROM build AS extractor
WORKDIR /app
COPY --from=build /app/marketplace-app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Runtime (JRE only, non-root user)
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
```

❌ **DON'T:**
```dockerfile
# ❌ layertools was REMOVED in Spring Boot 4.1 (issue #48568)
RUN java -Djarmode=layertools -jar app.jar extract
```

### 17.3 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Use `jarmode=tools` (not `layertools`) | `layertools` removed in Spring Boot 4.1 (M1, issue #48568) | [Spring Boot 4.1 — Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html) |
| Multi-stage build (build → extract → runtime) | Smaller final image (JRE only) | [Spring Boot — Efficient Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/efficient-images.html) |
| Copy layers in order: dependencies → loader → snapshot-deps → application | Maximizes Docker layer cache hits | Spring Boot docs |
| Run as non-root user (`USER app`) | Container security | OWASP Docker Security |
| Use `eclipse-temurin:25-jre-alpine` for runtime | Smallest JRE base, Java 25 LTS | [Adoptium](https://adoptium.net/) |
| Set `server.forward-headers-strategy=framework` behind reverse proxy | Trusted proxy handling | [Spring Boot — Forwarded Headers](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.forwarded-headers) |
| Set `spring.lifecycle.timeout-per-shutdown-phase=30s` | Graceful shutdown | [Spring Boot — Graceful Shutdown](https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.graceful-shutdown) |
| Optional: AOT Cache for Java 25+ | Faster startup (~30-50%) | [Spring Boot — AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html) |
| Kubernetes: set `terminationGracePeriodSeconds` > `timeout-per-shutdown-phase` | Allow graceful drain | Spring Boot deployment docs |

### 17.4 Health Checks

**Implemented design (2026-09-04, official-recipe aligned; reference updated 2026-09-06, CodeRabbit #241):** the repo's Dockerfile carries **no `HEALTHCHECK`** — the official Spring Boot 4.1 container recipe does not include one, and on Railway the platform owns deploy-time gating via the IaC config `.railway/railway.ts` (`service("app-java-v3", { healthcheck: "/actuator/health/liveness", ... })`). The snippet below remains the reference pattern for generic Docker hosts that lack a platform healthcheck:

```dockerfile
# Dockerfile HEALTHCHECK (generic-Docker reference pattern — not used on Railway)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
```

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health/liveness` | Liveness probe (is the app running?) |
| `/actuator/health/readiness` | Readiness probe (is it ready to serve?) |
| `/actuator/info` | Build info + git commit |
| `/actuator/prometheus` | Prometheus metrics |

**Reference:** [Spring Boot Actuator — Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

---

## 18. Spring Security 7.x Compliance

### 18.1 Breaking Changes (Spring Security 7.0)

> **Official doc** — [Spring Security — What's New in 7.0](https://docs.spring.io/spring-security/reference/7.0/whats-new.html):
> "Being a major release, there are a number of deprecated APIs that are removed in Spring Security 7."

**Removed APIs (must not use):**

| Removed | Replacement | Reference |
|---------|-------------|-----------|
| `and()` in `HttpSecurity` DSL | Lambda methods | Spring Security 7.0 whats-new |
| `authorizeRequests` | `authorizeHttpRequests` | Spring Security 7.0 whats-new |
| `MvcRequestMatcher` / `AntPathRequestMatcher` | `PathPatternRequestMatcher` | Spring Security 7.0 whats-new |
| OAuth2 password grant | (removed — use authorization code + PKCE) | Spring Security 7.0 whats-new |
| `ApacheDsContainer` (LDAP) | UnboundID | Spring Security 7.0 whats-new |
| Open SAML 4 | Open SAML 5 | Spring Security 7.0 whats-new |
| `AuthorizationManager#check` | `AuthorizationManager#authorize` | Spring Security 7.0 whats-new |

**New defaults (must adopt):**

| Feature | Default | Reference |
|---------|---------|-----------|
| PKCE in OAuth2 Authorization Server | **Enabled by default** | Spring Security 7.0 whats-new |
| SPA-based CSRF | `http.csrf(csrf → csrf.spa())` | Spring Security 7.0 whats-new |
| Multi-Factor Authentication | `@EnableMultiFactorAuthentication` | Spring Security 7.0 whats-new |
| Jackson 3 (was Jackson 2) | `JsonMapper.Builder` + `SecurityJacksonModules` | [Migration to 7.0](https://docs.spring.io/spring-security/reference/migration/index.html) |

### 18.2 DaoAuthenticationProvider (Spring Security 7.x)

✅ **DO:**
```java
@Bean
public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                    PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
}
```

❌ **DON'T:**
```java
DaoAuthenticationProvider provider = new DaoAuthenticationProvider();  // ❌ no-arg constructor removed
provider.setUserDetailsService(userDetailsService);                    // ❌ setter removed
```

> **Reference:** Spring Security 7.x Javadoc — `DaoAuthenticationProvider(UserDetailsService)` is the only public constructor. The no-arg constructor and `setUserDetailsService()` method were removed in 7.0.

### 18.3 Security Filter Chain Pattern (Spring Security 7.x)

✅ **DO:**
```java
@Bean
@Order(3)
public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())  // lambda — no .and()
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth  // authorizeHttpRequests — not authorizeRequests
            .requestMatchers("/api/v1/**").authenticated()
            .anyRequest().permitAll()
        )
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
}
```

❌ **DON'T:**
```java
http.csrf().disable()                  // ❌ no .and() in 7.x
    .authorizeRequests()                // ❌ removed — use authorizeHttpRequests
        .antMatchers("/api/**").authenticated()  // ❌ AntPathRequestMatcher removed
        .and()                          // ❌ .and() removed
    .oauth2ResourceServer();
```

### 18.4 What's New in Spring Security 7.1

> **Official doc** — [Spring Security — What's New in 7.1](https://docs.spring.io/spring-security/reference/whats-new.html):
> - Added `InetAddressMatcher`
> - Added `ConditionalAuthorizationManager`
> - Added `PreFlightRequestFilter` support
> - Added `RestClientOpaqueTokenIntrospector`
> - Added Programmatic MFA (`when` / `withWhen` conditions)
> - WebAuthn Authentication Events

### 18.5 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Use lambda DSL (no `.and()`) | Removed in 7.0 | [Spring Security 7.0 whats-new](https://docs.spring.io/spring-security/reference/7.0/whats-new.html) |
| `authorizeHttpRequests` (not `authorizeRequests`) | Removed in 7.0 | Spring Security 7.0 whats-new |
| `PathPatternRequestMatcher` (not `AntPathRequestMatcher`) | Removed in 7.0 | Spring Security 7.0 whats-new |
| `DaoAuthenticationProvider(userDetailsService)` constructor | Only constructor in 7.x | Spring Security Javadoc |
| PKCE enabled by default (don't disable) | Security best practice | Spring Security 7.0 whats-new |
| Jackson 3 (not Jackson 2) | Migration in 7.0 | [Migration to 7.0](https://docs.spring.io/spring-security/reference/migration/index.html) |
| `@EnableMultiFactorAuthentication` for MFA | New in 7.0 | Spring Security 7.0 whats-new |
| HSTS, CSP, Permissions-Policy via `.headers()` | Secure defaults | [Spring Security — Headers](https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html) |

---

## 19. Spring Cloud Gateway (when to use)

### 19.1 SCG vs Spring Security Filter Chain

> **Official doc** — [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway):
> "This project provides a library for building an API Gateway on top of Spring. Spring Cloud Gateway aims to provide a simple, yet effective way to route to APIs and provide cross-cutting concerns to them such as: security, monitoring/metrics, and resiliency."

**They are complementary, not alternatives:**

| Concern | Layer | Tool |
|---------|-------|------|
| Edge routing, rate limiting, request transformation | Edge / API Gateway | Spring Cloud Gateway |
| Authentication, authorization, CSRF, security headers | Application | Spring Security filter chain |

### 19.2 Two Flavors (WebFlux vs WebMVC)

> **Official doc** — [Spring Cloud Gateway — Reference](https://docs.spring.io/spring-cloud-gateway/reference/):
> "This project provides an API Gateway built on top of the Spring Ecosystem, including: Spring Framework 7, Spring Boot 4, and Project Reactor. There are two distinct flavors of Spring Cloud Gateway: Server and Proxy Exchange. Each flavor offers WebFlux and Web MVC compatibility."

| Flavor | Starter | Use Case |
|--------|---------|----------|
| Server WebFlux | `spring-cloud-starter-gateway-server-webflux` | Reactive, non-blocking gateway |
| Server WebMVC | `spring-cloud-starter-gateway-server-webmvc` | Servlet-based gateway (simpler) |

**This project:** Spring MVC (blocking) — if a gateway is needed, use Server WebMVC flavor.

### 19.3 Rules

| Rule | Rationale | Reference |
|------|-----------|-----------|
| Use SCG for **edge concerns** (routing, rate limit, transform) | Separation of concerns | [SCG project page](https://spring.io/projects/spring-cloud-gateway) |
| Use Spring Security for **app concerns** (auth, CSRF) | Separation of concerns | [Spring Security reference](https://docs.spring.io/spring-security/reference/) |
| Choose WebMVC flavor for Spring MVC apps | Stack consistency | SCG reference |
| SCG supports `RequestRateLimiter` with Redis backend | Distributed rate limiting | [SCG — RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc.html) |
| Don't replicate security rules in both SCG and Security filter chain | Single source of truth | Architecture principle |

---

## Appendix: References

### Spring Framework (mandatory)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/)
- [Spring Boot — How to Deploy](https://docs.spring.io/spring-boot/how-to/deployment/index.html) ⭐ mandatory
- [Spring Boot — Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)
- [Spring Boot — AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)
- [Spring Framework Reference](https://docs.spring.io/spring-framework/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Spring Security — What's New (7.1)](https://docs.spring.io/spring-security/reference/whats-new.html) ⭐ mandatory
- [Spring Security — What's New (7.0)](https://docs.spring.io/spring-security/reference/7.0/whats-new.html)
- [Spring Security — Migration to 7.0](https://docs.spring.io/spring-security/reference/migration/index.html)
- [Spring Security — Headers](https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/)
- [Spring Modulith Reference](https://docs.spring.io/spring-modulith/)
- [Spring Cloud Gateway](https://spring.io/projects/spring-cloud-gateway) ⭐ mandatory
- [Spring Cloud Gateway — Reference](https://docs.spring.io/spring-cloud-gateway/reference/)
- [Spring Guides](https://spring.io/guides)

### Java
- [Java 25 Language Specification](https://docs.oracle.com/javase/specs/)
- [Java Records](https://docs.oracle.com/en/java/javase/25/language/records.html)
- [Optional class](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Optional.html)

### Standards
- [RFC 7807 — Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc7807)
- [RFC 6238 — TOTP](https://datatracker.ietf.org/doc/html/rfc6238)
- [RFC 6749 — OAuth 2.0](https://datatracker.ietf.org/doc/html/rfc6749)
- [RFC 8252 — PKCE](https://datatracker.ietf.org/doc/html/rfc8252)
- [RFC 6797 — HSTS](https://datatracker.ietf.org/doc/html/rfc6797)
- [NIST SP 800-63B — Authentication](https://pages.nist.gov/800-63-3/sp800-63b.html)
- [NIST SP 800-57 — Key Management](https://nvd.nist.gov/800-57)
- [OWASP Cheat Sheets](https://cheatsheetseries.owasp.org/)

### Build & Testing (mandatory)
- [Maven — Guides Index](https://maven.apache.org/guides/index.html) ⭐ mandatory
- [Maven — Build Lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html)
- [Maven Enforcer Plugin](https://maven.apache.org/enforcer/)
- [JaCoCo](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
- [Surefire](https://maven.apache.org/surefire/) / [Failsafe](https://maven.apache.org/failsafe/)
- [JUnit 5](https://junit.org/junit5/)
- [Mockito](https://site.mockito.org/)
- [AssertJ](https://assertj.github.io/doc/)
- [Instancio](https://www.instancio.org/)
- [Testcontainers](https://www.testcontainers.org/)

### Libraries
- [MapStruct](https://mapstruct.org/)
- [SLF4J](http://www.slf4j.org/)
- [Micrometer](https://micrometer.io/)
- [Resilience4j](https://resilience4j.readme.io/)
- [Hibernate Envers](https://docs.jboss.org/envers/)
