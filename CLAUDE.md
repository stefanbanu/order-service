# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All verified working on this machine (Java 25, `mvnw` → Maven 3.10.0).
- Build + tests: ./mvnw verify
- Compile only: ./mvnw compile

```bash
./mvnw test                                          # run all tests (102)
./mvnw test -Dtest='OrderControllerTest'             # single test class
./mvnw test -Dtest='ClassName#methodName'            # single test method
./mvnw test -Dtest='Order*Test'                      # pattern
./mvnw spring-boot:run                               # run app → Tomcat on :8080
./mvnw package                                       # build executable jar in target/
./mvnw spring-boot:build-image                       # OCI image via Paketo buildpacks
```

There is no linter or formatter configured.

Prefer `./mvnw` over the system `mvn` — the wrapper pins Maven 3.10.0 and uses
`distributionType=only-script`, so it relies on a system Maven install for the binary but
still controls the version.

To reach the H2 console, it must be explicitly enabled — the `spring-boot-h2console`
dependency alone leaves it off (`/h2-console` 404s):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.h2.console.enabled=true
```

The in-memory database name is a **fresh random UUID on every boot**
(`jdbc:h2:mem:<uuid>`), logged at startup by `H2ConsoleAutoConfiguration`. Read the JDBC
URL out of the log to connect; don't hardcode it. Set `spring.datasource.url` to a fixed
value if you need a stable one.

## Spring Boot 4 starter names — do not "correct" these

This project is on **Spring Boot 4.1.1**, which split the old umbrella starters apart. The
`pom.xml` deliberately uses Boot 4 coordinates that look wrong to anyone used to Boot 3:

| In this pom (Boot 4)                     | The Boot 3 name you may reach for |
| ---------------------------------------- | --------------------------------- |
| `spring-boot-starter-webmvc`             | `spring-boot-starter-web`         |
| `spring-boot-h2console`                  | *(was part of devtools/web)*      |
| `spring-boot-starter-data-jpa-test`      | `spring-boot-starter-test`        |
| `spring-boot-starter-validation-test`    | `spring-boot-starter-test`        |
| `spring-boot-starter-webmvc-test`        | `spring-boot-starter-test`        |

Note there are **three separate `-test` starters**, one per slice, rather than one
`spring-boot-starter-test`. When adding a new capability, add both its starter and its
matching `-test` starter. Renaming any of these to the Boot 3 form breaks the build.

## Boot 4 relocations and replacements

Every row below was verified against the resolved classpath. Reaching for the familiar name
gives a compile error, or silently nothing:

| Familiar (Boot 3 / Spring 6) | What this project has |
| --- | --- |
| `...boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `...boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| `...boot.test.autoconfigure.orm.jpa.DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |
| `...boot.test.autoconfigure.orm.jpa.TestEntityManager` | `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager` — note this is **not** under `data.jpa` like `@DataJpaTest`; the two sit in different packages |
| `...boot.web.embedded.tomcat.TomcatWebServer` | `org.springframework.boot.tomcat.TomcatWebServer` |
| `@MockBean` | **removed.** Use `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`) |
| `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` — Boot 4 ships **Jackson 3** under the `tools.jackson` root; the `com.fasterxml` databind is not on the classpath at all |

`@SpringBootTest` (`org.springframework.boot.test.context`) did *not* move. Jackson
*annotations* also stay put at `com.fasterxml.jackson.annotation` (still 2.x) — only
databind and core moved to `tools.jackson`. MockMvc is unchanged and still available, though
Boot 4 also ships `RestTestClient` as an alternative.

JUnit Jupiter is **6.0.3**, not 5.x; the usual `org.junit.jupiter.api` imports still apply.

When in doubt, confirm a symbol exists before writing against it:

```bash
unzip -l ~/.m2/repository/org/springframework/spring-test/7.0.9/spring-test-7.0.9.jar | grep -i mockitobean
```

## Lombok is wired through explicit annotationProcessorPaths

`maven-compiler-plugin` declares Lombok under **two** executions — `default-compile` and
`default-testCompile` — each with its own `<annotationProcessorPaths>`. Declaring
`annotationProcessorPaths` replaces the default discovery of processors from the classpath,
which is why Lombok has to be listed explicitly here.

Consequence: adding another annotation processor (MapStruct, a mapper generator, etc.)
means adding it to **both** execution blocks. Adding it to only one, or to just the
dependency list, causes processors to silently stop running in the other compile phase —
typically surfacing as "cannot find symbol" on generated getters in test code only.

## Intentionally empty pom.xml elements

`<url/>`, `<licenses><license/></licenses>`, `<developers><developer/></developers>`, and
the empty `<scm>` block are **deliberate overrides**, not placeholders to tidy up. Maven
inherits these elements from the `spring-boot-starter-parent`, so without the empty
overrides the build would advertise Spring Boot's license and developers as this project's
own. Leave them, or fill them in with real values — but don't just delete them. (The
generated, gitignored `HELP.md` documents this under "Maven Parent overrides".)

## Architecture

Package root `com.banu.orderservice` (groupId `com.banu`), organised **by layer**, not by
feature:

```
controller/   OrderController          REST endpoints
service/      OrderService             transactions, entity <-> DTO mapping
repository/   OrderRepository          Spring Data JPA
dto/          OrderRequest, OrderResponse, OrderStatusUpdateRequest,
              CancelOrderRequest
entity/       Order, OrderStatus (owns the transition matrix)
exception/    OrderNotFoundException, InvalidStatusTransitionException,
              RefundFailedException, ApiExceptionHandler
client/       PaymentClient (outbound port), FakePaymentClient,
              PaymentException
config/       PaymentClientConfiguration — every `@Bean` method lives here
```

`client/` is the one package that is not a layer: it holds an outbound port and its
stand-in, not a tier of the request path. `config/` holds the `@Bean` definitions, so new
beans go there rather than next to the type they build.

One resource so far:

| Endpoint | Behaviour |
| --- | --- |
| `POST /orders` | 201 + `Location: /orders/{id}`; 400 on invalid input |
| `GET /orders/{id}` | 200, or 404 |
| `GET /orders?email=` | 200; `email` is **optional** — omitted lists all orders |
| `PATCH /orders/{id}/status` | 200 + updated order; 200 no-op if already in that status; 404 if unknown, 409 if the transition is not allowed or the order was modified concurrently, 400 if the status is missing or not a known name, **502 if a refund failed** — `{"status":"CANCELLED"}` is an alias for the cancel endpoint, so this **can move money** |
| `POST /orders/{id}/cancel` | 200 + updated order with `cancelledAt`/`cancellationReason`; refunds a `PAID` order; 200 no-op if already cancelled; 404 if unknown, 409 if `SHIPPED` or modified concurrently, 400 if the reason exceeds 255 chars or the body is unparseable, 502 if the refund failed |

### Invariants worth preserving

- **The table is `orders`, not `order`.** `ORDER` is a SQL reserved word; H2 rejects
  `create table order (...)` with a syntax error. `@Table(name = "orders")` on the entity is
  load-bearing — `OrderRepositoryTest` cannot even start a context without it.
- **DTOs are the trust boundary.** `OrderRequest` has only `customerEmail` and `amount`; the
  absence of `id`, `status` and `createdAt` is what makes them unsettable by a client.
  Posting them is silently ignored (Jackson is lenient about unknown properties), covered by
  tests in `OrderApiIntegrationTest`. Don't widen `OrderRequest` without re-reading those.
- **`status` is server-set to `NEW` at creation** and moves through
  `PATCH /orders/{id}/status` or `POST /orders/{id}/cancel`. `OrderRequest` still has no
  `status` field, so a client cannot pick the starting status.
- **The transition matrix lives on `OrderStatus`, not in the service.** `NEW -> PAID ->
  SHIPPED` is the happy path; `NEW` and `PAID` may go to `CANCELLED` instead; `SHIPPED`
  and `CANCELLED` are terminal. `OrderService.updateStatus` only enforces what
  `canTransitionTo` says, which is why the matrix is testable without a Spring context
  (`entity/OrderStatusTest`).
- **A PATCH to the status an order already has is an idempotent 200**, returning the order
  unchanged and issuing **no UPDATE** (verified against `show-sql`: the self-patch adds no
  `update orders` statement). This holds for terminal statuses too, so `SHIPPED -> SHIPPED`
  is 200 while `SHIPPED -> CANCELLED` is still 409. A client retrying after a timeout gets
  the same answer the first attempt would have given.
- **That no-op lives in `OrderService.updateStatus`, not in the matrix.** `OrderStatus` has
  deliberately **no self-edges** — `canTransitionTo(same)` is `false` for every status — so
  the current status never appears in the `allowed: [...]` list a 409 prints. Adding
  self-edges to `ALLOWED` would get the idempotency "for free" and corrupt those messages.
- **`OrderService.cancel` is the only path to `CANCELLED`.** `updateStatus` opens with
  `if (target == CANCELLED) return cancel(id, null);`, before its own no-op and before the
  matrix check. Without that delegation `PATCH {"status":"CANCELLED"}` was a cheaper route
  that stamped no `cancelledAt`, never refunded, and left the order in a state `cancel`
  then treated as already-cancelled — making the refund permanently unreachable. The matrix
  still carries the `NEW/PAID -> CANCELLED` edges; don't "fix" this by rejecting `CANCELLED`
  on PATCH, which would break both those edges and the idempotent `CANCELLED -> CANCELLED`
  200.
- **`cancel` flushes before it refunds, and refunds inside the transaction.** Both halves are
  load-bearing and neither is an accident:
  - `saveAndFlush` first means the versioned `update ... where id = ? and version = ?` holds
    the row lock until commit, so a concurrent cancel blocks, fails its own version check,
    and gets a 409 **without ever reaching `refund`**. Refunding first would let two
    requests pay out before either wrote.
  - The refund stays inside `@Transactional` because the rule is that a failed refund leaves
    the order `PAID`, and the rollback is what delivers it. The cost is a row lock held for
    the duration of an external call; an outbox is the real fix, not reordering those lines.
  - A **sequential** retry is deduplicated by the idempotency key, not by the ordering. If
    the refund succeeds but the commit fails, the order stays `PAID` and the 502 invites a
    retry — so `cancel` passes `PaymentClient.refund` a key of
    `order-{id}-{createdAt epoch millis}`, which the provider is contractually required to
    collapse into one refund.
  - One window the key cannot close: if a rolled-back attempt's refund was in fact processed
    and someone then PATCHes `PAID -> SHIPPED` (an allowed edge) before the client retries,
    the retry 409s, the order ships, and the refund is never reconciled. Real money, and it
    needs reconciliation outside `cancel`.
- **The idempotency key is built only from immutable fields.** `id` and `createdAt`
  (`@CreationTimestamp`, `@Column(updatable = false)`) are both unchangeable once the row
  exists, which is what makes every retry of the same cancellation reproduce the key —
  whatever happened to the row in between. Two consequences worth keeping:
  - **No mutable component, ever.** The key used to carry `@Version`, which worked only
    because no write path leaves an order in `PAID`. That is an unwritten invariant: the
    first write that preserves `PAID` (a PATCH correcting a typo'd `customerEmail`, say)
    would bump the version, mint a fresh key on retry, and pay out twice — silently.
    `OrderServiceTest` pins this with a stub whose `saveAndFlush` bumps the version the way
    Hibernate does, then asserts the key is **unchanged** by it.
  - **`id` alone is not enough.** Under `create-drop` with a per-boot database name,
    `IDENTITY` restarts at 1, so boot 2's order `1` would present the key boot 1's order `1`
    already burned, and a provider honouring the contract would replay that old success —
    moving no money and leaving a customer unpaid. The timestamp component is what rules that
    out. Note any boot-scoped salt would fix the collision and *break* the retry case, where
    the retry may arrive after a restart: the key has to be durable **and** per-order.
- **A second cancel is a write-free 200.** The already-`CANCELLED` early return precedes
  every setter, so no `UPDATE` is issued, no second refund happens, and a `cancellationReason`
  set earlier is never nulled out by a later reason-less PATCH.
- **`OrderResponse` components are appended, never reordered.** Tests and helpers build the
  record positionally, so inserting a component in the middle silently shifts every caller.
- **`FakePaymentClient` is a no-op behind `@ConditionalOnMissingBean`.** It is registered
  from `PaymentClientConfiguration`, not annotated `@Component`, so a real client replaces it
  instead of failing startup with `NoUniqueBeanDefinitionException` (the condition is only
  reliable on a `@Bean` method). It logs at **warn** and always reports success, so in a
  running app every `PAID` cancellation is cancelled-but-not-refunded, and the 502 branch is
  reachable only through a mocked `PaymentClient`.
- **Nothing here is authenticated.** `POST /orders/{id}/cancel` takes the id straight from
  the path with no principal and no ownership check, and that path moves money. Harmless
  only because the sole `PaymentClient` is a no-op — swapping in a real provider needs
  `spring-boot-starter-security` (plus its `-test` starter) and a check against
  `order.getCustomerEmail()` in the same change.
- **Email lookup is case-insensitive** (`findByCustomerEmailIgnoreCase`) because email
  domains are; values are stored exactly as submitted and only the lookup folds case.
- **`@Valid` on the controller parameter is what activates the DTO constraints.** Without
  it they are silently inert and every payload is accepted.
- **`Order` uses Lombok `@Getter @Setter @NoArgsConstructor`, deliberately not `@Data`** —
  `@Data` would generate `equals`/`hashCode` over all fields including the generated `id`,
  which misbehaves for unsaved entities and in collections.
- **`OrderNotFoundException` is a plain `RuntimeException`**, not a `ResponseStatusException`,
  so the service layer carries no HTTP concerns. `ApiExceptionHandler` maps it to 404.
  `RefundFailedException` follows the same shape, and being unchecked is also what triggers
  the rollback the 502 depends on.
- **The advice class is `ApiExceptionHandler`, and exceptions extend `RuntimeException`
  directly.** There is no `GlobalExceptionHandler` and no `BusinessException` in this repo;
  a stale note once claimed otherwise. Don't rename or introduce them on that basis.
- **Money is `BigDecimal`**, never `double`. The column is `precision = 19, scale = 2` and
  `OrderRequest` carries a matching `@Digits(integer = 17, fraction = 2)` so the API rejects
  what the database would otherwise truncate.

### Error responses

`ApiExceptionHandler` (`@RestControllerAdvice`) returns RFC 7807 `ProblemDetail` bodies:

```
404 {"status":404,"title":"Order not found","detail":"No order with id 9999","instance":"/orders/9999"}
409 {"status":409,"title":"Invalid status transition",
     "detail":"Order 1 is PAID and cannot become NEW; allowed: [SHIPPED, CANCELLED]"}
400 {"status":400,"title":"Invalid request","detail":"Request validation failed",
     "errors":{"amount":"must be greater than 0","customerEmail":"must be a well-formed email address"}}
400 {"status":400,"title":"Malformed request",
     "detail":"Request body is missing or not valid JSON for this endpoint"}
409 {"status":409,"title":"Concurrent modification",
     "detail":"The order was modified concurrently; retry the request"}
502 {"status":502,"title":"Refund failed",
     "detail":"Refund for order 1 failed; the order is unchanged"}
```

Spring already answers 400 for a failed `@Valid` without the handler — what the handler adds
is the `errors` map, and it reports **every** invalid field at once rather than the first.

A refused transition is **409, not 400**: the body parsed and the order exists, so what
conflicts is the order's current state rather than the request.

The `Malformed request` row is the `HttpMessageNotReadableException` handler. It matters
because an unparseable body never reaches `@Valid`, so **an unknown enum name such as
`{"status":"BOGUS"}` would otherwise answer 400 with a completely empty body** — verified
before the handler existed. Its detail is deliberately generic: Jackson's own message names
internal classes and echoes the rejected value. Note this handler also covers malformed
JSON on `POST /orders` and on `POST /orders/{id}/cancel`.

The 409 handler catches **`ConcurrencyFailureException`**, not just
`OptimisticLockingFailureException`. That breadth is deliberate: while `cancel` holds the row
lock across the refund call, a racing request can hit H2's lock timeout first, which arrives
as `CannotAcquireLockException` — a *pessimistic* failure. Narrowing this handler sends that
race to a bare 500 instead of the 409 it deserves.

Both new handlers **log** (`log.error` for a refund failure, `log.warn` for a concurrency
loss) because the `PaymentException` cause carries the provider's real error and reaches the
client in no form at all; a production 502 would otherwise be undiagnosable. The response
bodies deliberately say nothing extra.

### Persistence

H2 in-memory only; there is no production datasource. `ddl-auto: create-drop` (already the
default for an embedded datasource, set explicitly in `application.yaml` now that real
tables exist), so the schema is rebuilt per boot and **no data survives a restart**. No
Flyway or Liquibase, no `schema.sql`/`data.sql`. Introducing a real database means adding a
migration tool and revisiting `ddl-auto` first.

`spring.jpa.open-in-view` is set to `false`; leave it that way, or queries start firing
during view rendering.

`Order.version` (`@Version`, `@Column(nullable = false)`) is what makes the anti-double-refund
ordering in `cancel` work. Harmless under `create-drop`, but a migration tool arriving later
must add the column `NOT NULL DEFAULT 0` and backfill existing rows: Hibernate reads a `NULL`
version as a transient entity.

No profiles are defined — startup reports `No active profile set`.

## Testing

102 tests in seven classes, layered deliberately:

| Class | Scope |
| --- | --- |
| `entity/OrderStatusTest` | plain JUnit, no Spring — the transition matrix, parameterized |
| `service/OrderServiceTest` | plain JUnit + Mockito, no Spring — cancellation rules, and an `InOrder` pinning flush-before-refund |
| `controller/OrderControllerTest` | `@WebMvcTest` + `@MockitoBean OrderService` — status codes, headers, validation |
| `repository/OrderRepositoryTest` | `@DataJpaTest` against real H2 — derived query, timestamps, scale, `@Version`, cancellation round trip |
| `OrderApiIntegrationTest` | `@SpringBootTest` + `@AutoConfigureMockMvc`, `@Transactional` — controller through to H2 |
| `OrderCancelIntegrationTest` | same slice **plus `@MockitoBean PaymentClient`** — cancellation end to end, including the 502 rollback |
| `OrderServiceApplicationTests` | `contextLoads` smoke test |

`OrderCancelIntegrationTest` is a separate class rather than more methods on
`OrderApiIntegrationTest` because `@MockitoBean PaymentClient` changes the context cache key;
merging them would rebuild the context for every existing test and let a stubbed client leak
into tests that assume the real one.

Conventions that are intentional:

- **Request bodies are plain JSON string literals, not serialised objects.** This keeps the
  exact payload under test visible and avoids the Jackson 3 import change entirely.
- **The cross-layer test lives at the package root** because it belongs to no single layer;
  the slice tests mirror the main tree.
- `OrderApiIntegrationTest` is `@Transactional` so each test rolls back and list-length
  assertions stay independent of execution order.
- When asserting on a `JsonPath.read(...)` result, **assign it to a typed local first**:

  ```java
  Number id = JsonPath.read(json, "$.id");   // then id.toString()
  ```

  `read` is generic (`<T> T`), so `String.valueOf(JsonPath.read(...))` infers `T` as
  `char[]` to match an overload and fails at runtime with
  `Integer cannot be cast to [C`. It compiles cleanly, which is what makes it nasty.
- **A test that asserts something was *not* written must opt out of the test transaction**
  (`@Transactional(propagation = Propagation.NOT_SUPPORTED)`, with its own cleanup). Inside
  the class-level transaction the service merely joins it, so a throw only marks it
  rollback-only and a follow-up `GET` reads the flushed value back out of the shared
  persistence context. The 502-rollback tests in `OrderCancelIntegrationTest` would be
  decided by the identity map rather than by rollback without it. Same trap, same shape: a
  `@DataJpaTest` proving a column round-trips needs `entityManager.clear()` before reloading,
  or it re-reads the managed instance and never touches the row.
- **Comparing a `BigDecimal` argument uses `isEqualByComparingTo`, not `eq(...)`/`isEqualTo`** —
  `BigDecimal.equals` is scale-sensitive, so `42.5` and `42.50` are unequal. Capture the
  argument and compare numerically.
- **An `Instant` asserted for exact equality is stored `truncatedTo(MICROS)`** — H2 maps
  `Instant` to `TIMESTAMP(6)` while `Instant.now()` carries nanoseconds, so an untruncated
  value comes back trimmed and the assertion fails for a reason that has nothing to do with
  the code under test.

## Repo

`main`, pushed to `origin` at https://github.com/stefanbanu/order-service (public), plus the
unpushed `feature/cancel-order`. Note the typo in the first commit message ("Sptring");
fixing it now needs an interactive rebase rather than an amend.

