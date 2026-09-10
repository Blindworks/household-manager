---
name: value-annotation-single-constructor
description: How to combine @Value config injection with constructor injection without breaking Spring's single-constructor autowiring or unit-test construction
metadata:
  type: feedback
---

Two working patterns for mixing `@Value` with constructor-injected final fields in this
project, both avoiding the "two constructors" trap from commit 926812b (Spring only
auto-selects a constructor when there is exactly one; a second one, e.g. for tests, breaks
app startup with "No default constructor found"):

1. **Field injection** (`AirrohrPollingService` style): keep `@RequiredArgsConstructor` for
   the DI fields, add the `@Value("${...}")` field separately (not `final`, no
   `@RequiredArgsConstructor` coverage). Simple, but a unit test that builds the service via
   `new Service(...)` cannot set that field — it stays at its Java default (`null`/`0`/`false`)
   unless the test also sets it via reflection.
2. **Single manual constructor** (used in `NetworkConnectivityPollingService`): drop
   `@RequiredArgsConstructor`, write one constructor by hand covering all fields including the
   `@Value`-annotated parameter (`@Value("${network.gateway-ip:...}") String gatewayIp`). Since
   it's the only constructor, Spring autowires it without `@Autowired`, and unit tests just pass
   the literal value as a normal constructor argument — no reflection needed.

Prefer pattern 2 whenever a unit test needs to control the `@Value`-configured value (e.g. a
gateway IP, threshold used in comparisons the test asserts on). Pattern 1 is fine when the
`@Value` field is display-only (like `AirrohrPollingService.airrohrUrl`, only used in a status
DTO) and no test needs to control it.

See also [[usermanagement-tasks-10-12.md]]-adjacent commit 926812b for the original two-constructor
startup breakage this avoids.
