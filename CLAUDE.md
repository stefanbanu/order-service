# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All verified working on this machine (Java 25, `mvnw` → Maven 3.10.0).

```bash
./mvnw test                                          # run all tests
./mvnw test -Dtest='OrderServiceApplicationTests'    # single test class
./mvnw test -Dtest='ClassName#methodName'            # single test method
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

Boot 4 also moved some classes: Tomcat's web server now logs from
`org.springframework.boot.tomcat.TomcatWebServer`, not `org.springframework.boot.web.embedded.tomcat`.
Verify import paths against the 4.1.1 docs rather than from memory.

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

## Current state and architecture

This is a **bare Spring Boot skeleton**, not a partially-built service. The entire
application is `OrderServiceApplication` (a `@SpringBootApplication` with a `main`) plus a
`contextLoads` smoke test. `application.yaml` sets only `spring.application.name`.

What that means in practice — none of these decisions have been made yet, so there is no
existing convention to follow and no prior art to match:

- No entities, repositories, services, or controllers. Startup logs `Found 0 JPA repository interfaces`.
- No schema management: no `schema.sql`/`data.sql`, no Flyway or Liquibase, and
  `spring.jpa.hibernate.ddl-auto` is unset — Hibernate's default applies against H2.
- No persistence beyond in-memory H2. There is no production datasource; H2 is the only
  database, at `runtime` scope.
- No API layer, so no versioning or error-handling scheme.
- No profiles — startup reports `No active profile set`.
- `spring.jpa.open-in-view` is unconfigured and warns at every startup. Set it explicitly
  (almost certainly to `false`) when adding the first repository, which both silences the
  warning and avoids queries during view rendering.

Package root is `com.banu.orderservice` (groupId `com.banu`).

## Repo

Single `main` branch, pushed to `origin` at https://github.com/stefanbanu/order-service
(public). History is one commit.