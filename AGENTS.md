# Project instructions

## Project

Android-first backend for VPN access and premium subscriptions.

## Technology stack

- Java 17
- Spring Boot 3
- Maven
- PostgreSQL
- Spring Data JPA
- Liquibase
- MapStruct
- Lombok
- Jakarta Validation
- Spring WebClient
- JUnit 5
- Mockito
- Testcontainers
- Docker Compose

## Architecture

Use the following package structure:

- controller
- service
- service.impl
- repository
- model
- dto
- mapper
- client
- config
- exception
- scheduler

Controllers must not contain business logic.

Services must depend on abstractions where external systems are involved.

Repositories must only handle persistence.

DTOs must be separated from JPA entities.

MapStruct must be used for entity/DTO mapping.

## Coding rules

- Use constructor injection.
- Do not use field injection.
- Use records for immutable DTOs where appropriate.
- Do not return JPA entities from controllers.
- Use UUID for internal entity identifiers.
- Use BigDecimal for monetary values.
- Store timestamps as Instant.
- Use Flyway nowhere; database migrations must use Liquibase.
- Do not use H2 in tests.
- Integration tests must use PostgreSQL Testcontainers.
- Avoid unnecessary abstractions.
- Do not introduce Kafka or microservices without an explicit task.
- Never log tokens, VPN keys, configuration files, payment payload secrets or passwords.
- All external requests must have timeouts and error handling.
- Every implemented feature must include tests.

## Workflow

Before making significant changes:

1. Inspect the current repository.
2. Explain the proposed change.
3. List files that will be created or modified.
4. Wait for approval when the task changes architecture.
5. Implement only the requested scope.
6. Run Maven tests.
7. Summarize changes and unresolved risks.

## Commands

Build:

```bash
./mvnw clean verify
