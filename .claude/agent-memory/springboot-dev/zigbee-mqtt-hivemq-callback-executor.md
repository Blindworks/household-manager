---
name: zigbee-mqtt-hivemq-callback-executor
description: HiveMQ MQTT client 1.3.17 API pitfalls in ZigbeeMqttConfig — callback/executor chaining, RxJava-wrapped executor has no real capacity limit, disconnect vs. shutdown ordering, connect-chain duplication
metadata:
  type: project
---

`Mqtt3AsyncClient.Mqtt3SubscribeAndCallbackBuilder.Call.callback(Consumer<Mqtt3Publish>)` takes
only one argument and returns `Call.Ex`, which then exposes a separate `.executor(Executor)`
method. There is no two-arg `callback(callback, executor)` overload in
`com.hivemq:hivemq-mqtt-client:1.3.17` (verified via `javap` on the jar in
`~/.m2/repository/com/hivemq/hivemq-mqtt-client/1.3.17/`).

**Why this matters:** the plan doc `docs/superpowers/plans/2026-07-28-zigbee-ausfallerkennung.md`
(Task 3, decoupling MQTT message handling onto a dedicated single-thread executor) specified
`.callback(this::handle, handlerExecutor)`, which fails to compile with "Liste der tatsächlichen
Argumente hat eine andere Länge als die der formalen Argumente". The plan's own Step 2 anticipated
a possible failure but pointed at the wrong cause (missing `Executor` import) — the real fix is
chaining `.callback(this::handle).executor(handlerExecutor)`.

**How to apply:** whenever attaching a callback + executor to a HiveMQ MQTT3 subscribe builder
(`ZigbeeMqttConfig` in `backend/src/main/java/com/household/manager/zigbee/config/`), use the
two-call chain, not a two-arg method call. If upgrading the HiveMQ client version, re-verify with
`javap` on the new jar before trusting old plan snippets — the builder API is fluent/chained here,
not overloaded.

See also [[usermanagement]] for other HiveMQ/library-API verification lessons in this codebase
(pattern: verify third-party API signatures against the actual jar rather than assuming from
memory or plan docs).

## Further pitfalls found in code review (2026-07-28, Paket A / zigbee-ausfallerkennung)

**`ThreadPoolExecutor.execute()` passed to HiveMQ's `.callback(consumer).executor(executor)` has
no real capacity limit, even with a bounded queue.** HiveMQ wraps the given `Executor` in RxJava's
`Schedulers.from(executor)`. The resulting `ExecutorScheduler.ExecutorWorker` batches Runnables in
its own **unbounded internal queue** and only ever submits itself to the given executor when no
task of its own is currently running (`wip.getAndIncrement() == 0`) — so at most **one** task is
ever queued in *our* `ArrayBlockingQueue`. A bounded queue + `RejectedExecutionHandler` on this
executor is therefore dead code in normal operation; real backpressure for a hanging DB comes from
RxJava's own `observeOn` buffer (128 elements) further upstream, which throttles the broker side
instead. Fix applied: use an unbounded `LinkedBlockingQueue` with an honest Javadoc comment
instead of a capacity that can never be hit. If a `RejectedExecutionHandler` is kept anyway, it
will in practice only ever fire during executor shutdown — see next point.

**`ThreadPoolExecutor#shutdownNow()` still invokes the `RejectedExecutionHandler`** for tasks that
arrive after shutdown starts. If `stop()` shuts down the handler executor *before* disconnecting
the MQTT client, a message arriving in that window triggers the reject handler with a message
implying an application error (e.g. "hanging DB"), when it's actually normal shutdown. Fix:
disconnect the MQTT client **first**, shut down executors **after**; additionally guard the reject
handler with `executor.isShutdown()` and log at `debug` (not `error`) in that branch.

**HiveMQ's `automaticReconnect` does not deduplicate concurrent connect chains.** If application
code drives its own resubscribe-retry loop from `addConnectedListener` (e.g. for unbounded
resubscribe-on-failure), every connect event starts a *new* retry chain without cancelling an
older one still waiting on a scheduled retry. If the old chain's stale retry later succeeds against
the now-reconnected client, you get two live subscriptions on the same topic filter — every
message processed twice (duplicate DB rows, flows firing twice), silently. Fix: an
`AtomicInteger subscribeGeneration` bumped once per `addConnectedListener` invocation; each retry
attempt closes over the generation it was started with and checks it both before sending the
subscribe **and** inside `whenComplete` before scheduling a follow-up or logging success — a stale
generation aborts silently with no further action.

**`ctx.getSource() == MqttDisconnectSource.USER`** distinguishes a self-initiated
`client.disconnect()` (as in `stop()`) from a real network-level disconnect in
`addDisconnectedListener`. Without this check, every regular shutdown logs a misleading
"Reconnect laeuft" warning — counterproductive in code whose whole point is making real failures
visible without noise.

All four fixed in `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`,
commit `4495d38` on `feature/zigbee-ausfallerkennung`. Lesson for future review of this file: verify
claims about executor/queue behavior against the actual wrapping library (HiveMQ wraps in RxJava),
not just against `java.util.concurrent` semantics in isolation.
