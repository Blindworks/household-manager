---
name: flowengine-stage3a
description: Node-RED-inspired flow engine (com.household.manager.flowengine) — 13-task rollout, trigger/handler contracts, node context conventions
metadata:
  type: project
---

New flow/automation engine (`com.household.manager.flowengine`) built across a 13-task
plan (tracked as internal tasks #15-#27). Tasks 1-5 = model/parser, FlowMessage/handler
contracts/StateComparator, DebugBuffer/FlowGraph/FlowValidator, engine core
(FlowRegistry/FlowEngine/executor). Task 6 (commit `d9ad09a`) = `EntityStateTriggerHandler`
+ `FlowEngineListener`, wiring [[entitystate-facade]]'s `EntityStateChangedEvent` into the
engine. Task 7 (commit `f62dce4`) = `ScheduleTriggerHandler` (Cron), see below. Task 8
= `EntityConditionHandler` + `DebugNodeHandler`. Task 9 (commit `8ee2ce4`, hardened same
day in `855513b`) = `DelayNodeHandler` + `RateLimitNodeHandler`, see below. Task 10
(commit `7b0b2f5`) = `AlexaAnnounceNodeHandler` + `SwitchDeviceNodeHandler`, see below.
Task 11 (commit `661c5db`) = `FlowService` + `FlowEngineBootstrap`, see below.
Task 12 (commit `172f6a3`) = `FlowController` + 5 DTOs, see below. Post-Task-12 review fix
(commit `a8bcbc9`) = `FlowService.require()` now throws `ResourceNotFoundException` instead
of `IllegalArgumentException`, so unknown flow id is 404 on every endpoint (GET/deploy/
enable/disable/delete/update), not just `GET /{id}`. `inject()`'s own
`IllegalArgumentException`s ("flow not deployed" / "unknown node") deliberately stay 400 —
the flow exists there, it's just in the wrong state. `FlowControllerTest` gained
`mutatingEndpointReturns404ForUnknownFlow` (mocks `deploy()` throwing
`ResourceNotFoundException`, asserts 404) to lock this in at the controller/advice-mapping
level. **Lesson: when a service has one `require(id)`-style existence check reused by
several mutating methods, verify from the start which exception type feeds
`GlobalExceptionHandler` — `IllegalArgumentException` and `ResourceNotFoundException` both
exist in this codebase mapped to different status codes (400 vs 404), and it's easy to
default to `IllegalArgumentException` (as Task 11 did) without noticing the existing-resource
handler is the more correct fit for a straightforward not-found case.** Task 13 remaining:
final overall verification + review.

## Task 12: FlowController + DTOs — thin controller, no new service methods needed
`FlowController` (`@RestController @RequestMapping("/v1/flows")`, plain constructor
injection of `FlowService`, `DebugBuffer`, `List<NodeHandler>`) is a pure pass-through
over the existing `FlowService` API from Task 11 — no new service-layer methods were
needed, confirming the Task 11 API was already REST-shaped. Route highlights:
`GET /v1/flows/node-types` sits above `GET /v1/flows/{id}` in the file but ordering
doesn't matter to Spring — static segments always win over path variables, no
`@Order`/regex needed. `GET /v1/flows/{id}` returns `ResponseEntity.notFound()` built
directly from `flowService.getById(id)` being empty (no `ResourceNotFoundException`
thrown) — deliberately different from other controllers in this codebase that throw
`ResourceNotFoundException` and let `GlobalExceptionHandler` build the 404; both are
fine, this one just returns the empty body Spring's `ResponseEntity.notFound()` gives
you rather than the `ErrorResponse` shape. `POST .../inject` accepts an optional raw
`Map<String,Object>` body with a `payload` key, single `@SuppressWarnings("unchecked")`
helper to extract it — `FlowService.inject` already tolerates a null/empty payload map.
`GET /v1/flows/node-types` builds `NodeTypeResponse` per handler with `trigger =
handler instanceof TriggerNodeHandler` (no explicit "isTrigger" flag on `NodeHandler`
itself, reusing the existing type hierarchy from Task 3 instead of adding one), sorted
by `type()` for stable ordering. Test: `FlowControllerTest`, pure Mockito +
`MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new
GlobalExceptionHandler())` (no `@SpringBootTest`/`@WebMvcTest`), consistent with every
other TDD task in this rollout. (Originally this note said `FlowService.require()` fed
`GlobalExceptionHandler`'s `IllegalArgumentException` → 400 handler for all mutating
endpoints — that was the inconsistency a review caught right after Task 12 landed; see
the post-Task-12 fix entry above, `require()` now throws `ResourceNotFoundException` →
404 instead.)

## Task 11: FlowService + FlowEngineBootstrap — deploy orchestration, no new abstractions needed
`FlowService` (`@Service @RequiredArgsConstructor`) does plain CRUD (`getAll`/`getById`/
`create`/`update`, all `@Transactional`) plus the deploy lifecycle: `deploy(id)` parses
the draft, runs it through `FlowValidator`, and **only on `result.valid()`** copies
draft->deployed (+ `deployedAt`), saves, and calls `registry.deploy(id, definition)` —
invalid drafts leave the previous deployed state and registry entry completely untouched
(no partial writes). `setEnabled(id, false)` calls `registry.undeploy(id)`;
`setEnabled(id, true)` re-registers from `deployedDefinition` if one exists (a flow that
was deployed while disabled has a DB row but no live registry entry — this is intentional,
matches `FlowEngineBootstrap`'s `findByEnabledTrueAndDeployedDefinitionNotNull` bootstrap
query). `delete(id)` undeploys then `flowRepository.deleteById(id)`. `inject(flowId,
nodeId, payload)` is the manual-fire path for testing a deployed flow by hand: looks up
the node via `registry.graph(flowId)` (throws `IllegalArgumentException` if the flow isn't
deployed or the node isn't a deployed `TriggerNodeHandler`), builds a message with
`timestamp` + `triggerNodeId` merged with the caller's payload, and calls
`engine.emitAsync(flowId, nodeId, 0, msg)` directly — bypasses `TriggerNodeHandler.
register()`/`onEntityEvent()` entirely since this is a synthetic fire, not a real trigger
condition.

`FlowEngineBootstrap` (`@Component`, `@EventListener(ApplicationReadyEvent.class)`) is the
one place that calls `registry.setEngine(engine)` — resolves the `FlowRegistry`<->
`FlowEngine` circular dependency lazily instead of via `@Lazy`/constructor tricks — then
loops `flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()` and registers each.
Per-flow try/catch around `registry.deploy(...)` so one corrupt/invalid deployed flow
(shouldn't happen given `deploy()`'s validate-before-write invariant, but DB rows can be
hand-edited or migrated) logs and skips rather than blocking every other flow from loading
at startup.

Test (`FlowServiceTest`, pure Mockito, no `@SpringBootTest`) builds a **real**
`FlowRegistry`/`FlowValidator`/`FlowDefinitionParser` (only `FlowRepository` and
`FlowEngine` are mocked) with a minimal anonymous `TriggerNodeHandler` — same pattern as
the node-handler tests: exercise the real registry/graph/validator wiring, only mock the
persistence and the async engine boundary. No new abstractions were needed; every type
`FlowService` depends on (`FlowRepository`, `FlowDefinitionParser`, `FlowValidator`,
`FlowRegistry`, `FlowEngine`, `ValidationResult`, `FlowMessage`, `TriggerNodeHandler`)
already existed unchanged from Tasks 1-10 — confirmed by reading each file before writing
the service rather than assuming signatures from the task prompt.

## Task 10: AlexaAnnounceNodeHandler + SwitchDeviceNodeHandler — stateless action nodes
Both are plain `@Component @RequiredArgsConstructor`, 1 output port, `handle()` always
returns `NodeResult.single(message)` (pass the incoming message through unchanged after
the side effect) so they chain like Node-RED action nodes. No `ctx.state()` use, no
`register()` override needed — nothing to clean up on undeploy.

`AlexaAnnounceNodeHandler` (`type()` = `"alexa-announce"`) wraps
`AlexaAnnouncementService.announce(text, serialNumbers, AlexaTtsMode)` (existing service,
see [[alexa-tts-integration]]). Config keys: `text` (placeholders `{entityId}`,
`{newState}`, `{oldState}` resolved via simple `String.replace` against `FlowMessage.get(key)`,
missing/null values render as empty string — no exception), `mode` (`SPEAK`/`ANNOUNCE`,
validated via `AlexaTtsMode.valueOf` catch-and-record-error, not a manual string check),
`deviceSerials` (`List<String>` via `NodeConfig.stringList`).

`SwitchDeviceNodeHandler` (`type()` = `"switch-device"`) wraps
`SmartDeviceService.turnOn(Long)`/`turnOff(Long)`. Config keys: `deviceId` (read via
`NodeConfig.integer()` which returns `Optional<Integer>` — note `SmartDeviceService`
takes `Long`, so the handler does `.orElseThrow().longValue()` to convert), `action`
(`"on"`/`"off"` string, no enum — validated with a plain equals check since there's no
existing enum for this two-value action).

Both tests are pure Mockito (`@ExtendWith(MockitoExtension.class)`, `ctx` passed as
literal `null` since neither handler touches it), matching the Tasks 6-9 test style —
confirms `NodeContext` is only needed by nodes with timer/state bookkeeping, not by
simple pass-through action nodes.

## Task 7: ScheduleTriggerHandler (Cron) — no shared state, no sync needed
`ScheduleTriggerHandler implements TriggerNodeHandler` (`type()` = `"schedule-trigger"`,
1 output port). `watchedEntityId()` always `Optional.empty()` (time-driven, not entity-driven).
`validate()` requires a `cron` config key and checks it with Spring's
`CronExpression.isValidExpression(...)`. `register(config, ctx)` calls
`ctx.scheduler().schedule(() -> ctx.emit(0, FlowMessage.of(Map.of("timestamp", ...,
"triggerNodeId", ctx.nodeId()))), new CronTrigger(cron))` and returns
`() -> future.cancel(false)` as the undeploy cleanup — unlike Task 6's
`EntityStateTriggerHandler`, this node keeps **no state in `ctx.state()`**: the
`ScheduledFuture` is only closed over by the returned cleanup `Runnable`, not written to the
shared per-node `ConcurrentMap`. Since `register()` is called once per deploy (per
`NodeContext` instance, which itself lives one-per-deployed-node until redeploy), there's no
concurrent-access hazard here and the `synchronized (ctx.state())` pattern from Task 6's
takeaway does not apply — that pattern is specifically for state stored in `ctx.state()`
that concurrent `handle()`/`onEntityEvent()` calls could race on; a trigger with no such
state doesn't need it. Test: pure Mockito (`@ExtendWith(MockitoExtension.class)`, mocked
`TaskScheduler` + `ScheduledFuture`, `ArgumentCaptor<Runnable>` capturing the scheduled task
to invoke it manually and assert `ctx.emit` fired), matching the Task 6 test style.

## Task 9: DelayNodeHandler + RateLimitNodeHandler — applied the Task-6 synchronized takeaway
`DelayNodeHandler` (`type()` = `"delay"`, 1 output port, config key `seconds`): originally
shipped stateless (`ctx.scheduler().schedule(...)` with no tracking) — flagged as an open
leak (redeploy mid-delay would still fire into a torn-down flow) and hardened same-day in
commit `855513b`. Fix: pending `ScheduledFuture`s are now parked in a
`ConcurrentHashMap.newKeySet()` under `STATE_KEY_PENDING = "pendingDelays"` in `ctx.state()`
(fan-in safe — N in-flight messages = N futures in the set). Self-removal on fire uses a
1-element array holder (`ScheduledFuture<?>[] holder`) since the future can't reference
itself before `schedule()` returns. `FlowRegistry` gained a generic
`cancelScheduledFutures(DeployedFlow)` (private) called from both `deploy()` (tearing down
the replaced `old` flow) and `undeploy()`, alongside the existing `runCleanups(...)` call —
it walks every node's `ctx.state()` values (and one level into any `Collection` values) and
calls `.cancel(false)` on anything that's a `ScheduledFuture`. This makes any future node
with timer state auto-safe on teardown without a bespoke `register()` cleanup, and is
additive/idempotent alongside Task 6's `EntityStateTriggerHandler.register()` cancel (same
future cancelled twice is harmless). Regression test: `FlowEngineTest.
undeployCancelsPendingDelayFutures()` — real `DelayNodeHandler` in the handler list, a
mocked `TaskScheduler` whose `schedule(...)` returns a mocked `ScheduledFuture`, deploy →
`runFrom` to trigger the delay's `handle()` → `undeploy()` → `verify(future).cancel(false)`.

`RateLimitNodeHandler` (`type()` = `"rate-limit"`, 1 output port, config key
`minIntervalSeconds`): takes an injectable `Clock` (public no-arg ctor defaults to
`Clock.systemDefaultZone()`, package-private `Clock` ctor for tests) and stores
`Instant lastPassed` under `STATE_KEY_LAST_PASSED` in `ctx.state()`. Applied the Task-6
lesson directly instead of waiting for review to flag it: the get-check-put sequence is
wrapped in `synchronized (ctx.state())` from the first cut, since concurrent `handle()`
calls on the same node could otherwise both read `lastPassed` as stale and both pass
through. This is the pattern to keep reusing for any future node with read-modify-write
state in `ctx.state()`.

## Stage 3b Task A2: fields()/portLabels() overrides in all 8 handlers (commit `fc27ad9`)
Followed Task A1 (`NodeFieldType`, `NodeFieldDescriptor` with static `field(key,label,type,required)`
and `enumField(key,label,required,options)` factories, `NodeHandler` default `fields()`/
`portLabels()`). Task A2 was a pure catalog-declaration pass — each handler's `fields()`
override is a static `List.of(...)` literal, no runtime dependency on the injected service,
so passing `null` for the constructor arg in tests (`EntityStateTriggerHandler`,
`EntityConditionHandler`, `AlexaAnnounceNodeHandler`, `SwitchDeviceNodeHandler` all take a
service) is safe and was the intended test style. Only `EntityConditionHandler` also
overrides `portLabels()` (`List.of("wahr", "falsch")` — the only 2-output-port handler);
all others rely on the `NodeHandler` default (`outputPorts()` copies of `"Ausgang""`, or
empty for `DebugNodeHandler`'s 0 ports). Test `NodeCatalogFieldsTest` (7 cases, pure
value-object assertions, no Spring context) — red-then-green confirmed (7/7 errors on first
run since no handler had `fields()` yet, `EntityConditionHandler.portLabels()` default also
failed as `[Ausgang, Ausgang]` vs expected `[wahr, falsch]`). Full `flowengine` + `FlowController`
suite (73 tests) re-run after the change with zero regressions.

## Core contracts (already exist, don't recreate)
- `NodeHandler`: `type()`, `outputPorts()`, `validate(config)`, `handle(msg, config, ctx)`,
  default `configSchema()`. One `@Component` Spring bean per node type; `FlowRegistry`
  collects all `List<NodeHandler>` beans into a type->handler map at construction.
- `TriggerNodeHandler extends NodeHandler`: no input — `handle()` defaults to
  `NodeResult.none()`. Real work happens in `onEntityEvent(event, config, ctx)` (default
  no-op, override for entity-driven triggers) and `register(config, ctx)` (default no-op,
  override for e.g. cron scheduling — returns a cleanup `Runnable` run on undeploy/redeploy).
  `watchedEntityId(config)` returns `Optional<String>` — used by `FlowRegistry` to build the
  entityId->trigger index; **must return `Optional.empty()` for non-entity triggers** (e.g.
  the upcoming ScheduleTrigger in Task 7) since only `register()` applies there.
- `NodeContext`: per-node runtime handle (`flowId()`, `nodeId()`, `state()` — a
  `ConcurrentMap`, `emit(port, msg)`, `scheduler()`, `debug(label, msg)`). One instance per
  deployed node, lives until redeploy/restart (`FlowRegistry.EngineNodeContext`).
- `NodeConfig(Map<String,Object> values)`: null-tolerant, typed accessors `string()`,
  `integer()`, `stringList()` — all `Optional`/empty-safe, never throws on missing/bad keys.
- `StateComparator.matches(state, operator, value)`: string-based, numeric-aware
  (`<`,`<=`,`>`,`>=` only match if both sides parse as `BigDecimal`; `==`/`!=` fall back to
  string equality when not both numeric). `null` state/operator/value always → `false`.
- `FlowMessage(Map<String,Object> values)`: immutable, null-tolerant (unlike `NodeConfig`'s
  `Map.copyOf`-adjacent approach, uses a manual null-tolerant copy since trigger messages
  legitimately carry `null` values like `oldState`). `.with(key,val)` returns a new copy.

## NodeContext.state() concurrency: plain put/remove was NOT enough — review caught it
The interface docs warn compound get-then-put updates must go through
`compute`/`merge`/`putIfAbsent` since multiple executor threads can call `handle()`/
`onEntityEvent()` on the same node concurrently. Task 6's first cut of
`EntityStateTriggerHandler`'s timer bookkeeping (`STATE_KEY_TIMER` pending-timer
cancel/replace) used plain `state().put(...)`/`state().remove(...)` as separate
per-call ops, accepted at the time as an OK v1/single-household tradeoff — **this was
wrong and got flagged in quality review** (commit `bd91955`, same session): two
concurrent events on one node could race cancel-then-replace and leak a
`ScheduledFuture`. Fix applied: wrap the whole cancel-then-put sequence in
`synchronized (ctx.state())` (the state map instance itself as the lock, since it's a
plain `ConcurrentHashMap` per node and the critical section is short with no I/O besides
`scheduler().schedule(...)` which just enqueues). Same commit also added `register()` —
Task 6 had forgotten to override the `TriggerNodeHandler.register()` default no-op, so a
pending dwell timer wasn't cancelled on undeploy/redeploy and could fire late into a
*new* graph deployed at the same flowId; `register()` now returns
`() -> cancelTimer(ctx)` as the deploy cleanup.
**Takeaway for Tasks 7-9 (cron state, rate-limit counters, any other node with
timer/counter state in `ctx.state()`): synchronize on `ctx.state()` for
read-modify-write sequences from the start, and always override `register()` to clean up
any scheduled work — don't defer either to "if review catches it."**

## FlowEngineListener: deliberately not @TransactionalEventListener
Listens for `EntityStateChangedEvent` (published by `EntityStateService.reportState` after
commit, see [[entitystate-facade]]) and dispatches matching triggers via `FlowRegistry.
triggersFor(entityId)` on the dedicated `flowEngineExecutor` bean (2-4 threads, defined in
`FlowEngineConfig`). Using `@TransactionalEventListener` would silently drop the event
whenever there's no active transaction around the publish — which is the *normal* case for
polling-integration state reports (Tasmota/Shelly/Airrohr/DWD run outside any tx), so plain
`@EventListener` is correct here, not an oversight.

## Post-Task-13 final-review hardening (commit `1cb8cb9`)
Two small fixes from the final review, done in one commit: (1) `FlowEngineConfig` gained a
second bean `flowTaskScheduler` (`ThreadPoolTaskScheduler`, pool 2, prefix `flow-timer-`),
and `FlowEngine`'s constructor `TaskScheduler scheduler` param got `@Qualifier
("flowTaskScheduler")` — previously flow cron/delay/dwell timers silently shared the
`SchedulingConfig` `taskScheduler` bean (prefix `polling-`, pool 4) also used by Airrohr/
Tasmota/Weather/Shelly/AnkerSolix pollers, coupling flow-timer capacity to unrelated polling
load. (2) `DebugBuffer.clearFlow(long)` existed since Task 4 but was never called anywhere —
wired into `FlowRegistry.undeploy(long)`, guarded by `if (engine != null)` (mirrors the
existing `@Setter FlowEngine engine` null-window before `FlowEngineBootstrap.
setEngine()` runs), calling `engine.debugBuffer().clearFlow(flowId)` right after the existing
`runCleanups`/`cancelScheduledFutures` calls. Deliberately **not** cleared in `deploy()`'s
old-flow teardown path — debug history surviving a redeploy is useful, only
disable/delete should drop it (both route through `undeploy`).

**Gotcha this surfaced**: `FlowServiceTest` mocks `FlowEngine` (`@Mock private FlowEngine
engine`) and never stubbed `engine.debugBuffer()` — worked fine before because `undeploy()`
never touched it. Once `undeploy()` started calling `engine.debugBuffer().clearFlow(...)`,
the unstubbed mock method returned `null` (Mockito default for object-returning methods) and
NPE'd inside `disableUndeploysEnableRedeploys`/`deleteUndeploysAndDeletes`. Fix: added
`lenient().when(engine.debugBuffer()).thenReturn(new DebugBuffer())` in `setUp()` — a real
`DebugBuffer` instance (cheap, no deps), not another mock, since nothing needs to verify
interactions on it. **Lesson: when wiring a previously-dead getter/method into new production
code, grep test files for `@Mock`s of the type that owns it — an unstubbed mock returning
null for a newly-exercised path is a very easy miss, and it's not "softening the test" to add
the missing stub, since the assertions themselves don't change.** This is the same
`@Mock FlowEngine` pattern used in `FlowEngineTest`'s `undeployCancelsPendingDelayFutures`,
but that test constructs a **real** `FlowEngine` (with a real `DebugBuffer`), so it was
unaffected — only genuinely mocked `FlowEngine` instances need the stub.

## Flow-import feature (branch `feature/flow-import`, post-13-task-rollout)
New 2-task plan layered on top of the finished 13-task rollout: Task 1 = `FlowService.
importFlow(Integer schemaVersion, String name, String description, String definitionJson)`
(already done/committed before this note). Task 2 (commit `16dd0bd`) = `ImportFlowRequest`
record DTO (`schemaVersion`, `name`, `description`, `definition` as raw `JsonNode`) +
`POST /v1/flows/import` on `FlowController`, thin delegation:
`request.definition() == null ? null : request.definition().toString()` then
`flowService.importFlow(...)` + existing `toDetail()`. **Gotcha: the task prompt's test
step said "create FlowControllerTest.java" with a from-scratch plain-Mockito template
(`new FlowController(...)`, no MockMvc, asserting via record accessors like
`response.getId()`) — but `FlowControllerTest` already existed with 8 MockMvc-based tests
(`MockMvcBuilders.standaloneSetup(...).setControllerAdvice(new GlobalExceptionHandler())`,
see Task 12 note above) and `FlowDetailResponse`/`FlowSummaryResponse` are records, so
`getId()`/`isEnabled()` don't exist (only `id()`, `enabled()`). Writing the prompt's test
verbatim would have both deleted 8 passing tests and failed to compile. Fix: added one
`@Test` method to the existing file, MockMvc style consistent with its siblings, JSON-path
assertions instead of record accessors.** Always read the target test file before creating
it per a task prompt — task descriptions can drift from the file's actual current state,
especially on multi-session plans. Full suite re-verified after: 9/9 `FlowControllerTest`
pass, all other `flowengine`/controller unit tests green; `HealthControllerTest`'s 2 errors
are a pre-existing local-DB auth failure unrelated to this change (no local MariaDB creds
configured in this environment).

## Test approach that worked (Task 6)
Pure Mockito unit test for `EntityStateTriggerHandler` (`@ExtendWith(MockitoExtension.class)`,
manual anonymous-class `NodeContext` capturing emitted messages into a `List`, no
`@SpringBootTest`) — consistent with the [[entitystate-facade]] testing note. No test existed
for `FlowEngineListener` in Task 6 instructions (wiring-only class, exercised indirectly once
Task 11's FlowService/bootstrap task adds `@SpringBootTest`-level coverage) — flagged here in
case a later review expects listener-level tests that were never actually requested.
