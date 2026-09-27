---
paths: ["src/**/*.java"]
---

# Java 21 / Spring Boot conventions for codereview-app

These rules apply whenever Java source files are created or edited in this repository.

## Naming

- Standard Java naming conventions: `PascalCase` for classes/records/interfaces, `camelCase` for methods/fields/variables, `SCREAMING_SNAKE_CASE` for constants.
- Package names stay lowercase, no underscores (`com.codereview.app.tasks`, not `com.codereview.app.task_module`).

## Records for DTOs

- Use `record` for immutable data carriers — request/response DTOs (`LoginRequest`, `LoginResponse`) and simple domain models with no behavior (`Task`). Don't hand-write a class with a constructor, getters, `equals`/`hashCode` when a record does the same job.

## Optional over null

- Service methods that may not find a result return `Optional<T>`, never `null`. Controllers translate an empty `Optional` into the appropriate HTTP response (e.g. `404 Not Found`) rather than letting a `null` propagate.
- `Optional` is a return type only — don't use it for method parameters or fields.

## No business logic in controllers

- Controllers only handle HTTP concerns: request mapping, status codes, request/response (de)serialization. Decision-making, validation, and state mutation belong in the service layer.
- A controller method should read as a thin translation from HTTP to a service call, not contain business rules itself.

## Constructor injection

- Inject dependencies through the constructor, never with field-level `@Autowired`. A class with a single constructor doesn't need `@Autowired` at all — Spring wires it implicitly.
- Keep injected dependencies in `private final` fields.

## Tests live next to the code they test

- Tests mirror the package of the class under test, under `src/test/java` (e.g. `com.codereview.app.tasks.TaskServiceTest` tests `com.codereview.app.tasks.TaskService`) — never a separate, loosely-organized test tree.
- Prefer a plain JUnit unit test (no Spring context) for services, and `@WebMvcTest` for controllers instead of a full `@SpringBootTest` when only the web layer is under test.
