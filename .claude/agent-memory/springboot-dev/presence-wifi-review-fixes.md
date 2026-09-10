---
name: presence-wifi-review-fixes
description: Review round on PresencePollingService.refreshNow() (2026-08-26) - exception relocated out of network, javadoc precision, concurrency test hardening
metadata:
  type: feedback
---

Applied 2026-08-26 on `feature/presence-wifi` (commit 5cb1c92, on top of
[[presence-manual-refresh]] e6b491f). Backend-only task while a parallel
agent fixed the frontend in the same session - staged/committed by explicit
path only, never a broad `git add`, to avoid sweeping up the other agent's
untracked frontend files.

**Reuse-across-package-boundary calls need re-checking once a second caller
exists.** The original task explicitly asked "flag if reusing
`network.TooManyRequestsException` for presence is awkward" and I judged it
fine (plain public class, no network-specific content). The review caught
what I missed: once a second package depends on a class purely for its
*type*, the exception's *location* stops being neutral - its javadoc had
already drifted to describe only the original caller ("Speedtest-Trigger"),
and `presence` now had a dependency on `network` for nothing but an
exception. Fix: promote it to `com.household.manager.exception` (where
`ResourceNotFoundException`/`DuplicateEntityException` already live) and
generalize the javadoc to name both callers by mechanism (overlap-guard vs.
cooldown), not by feature name - so a third caller doesn't stale it again.
**Lesson:** "is this reuse OK" is not a one-time answer; re-ask it whenever
a class picks up a caller outside its original package.

**Javadoc scoping errors follow a pattern in this repo: a true claim
generalized past what it actually covers.** All three items in this review
were the same shape - "X is not swallowed" was true only for one sub-step,
"overlap is safe" was true only for one collaborator, "throws
IllegalStateException" was true but undocumented *why* (a deliberate
precedent-following choice, not a default). Fix pattern: keep the true
core claim, add one clause naming exactly what it does NOT cover and what
the accepted cost is - matching the existing house style (see
`TractiveHomeResolver`, `ZigbeeStreamMonitor` javadoc in CLAUDE.md) of
naming the price rather than only the safe part.

**Threaded test only proves what it asserts, not what it assumes.** A test
starting a bare `new Thread(runnable)` and asserting on a *second*,
synchronous call can pass even if the first thread silently threw before
reaching the code path under test - the assertion, `join()`, and any
follow-up call are all satisfied by definition, regardless of what actually
happened on that thread. Fix: `Thread.setUncaughtExceptionHandler` into an
`AtomicReference<Throwable>`, assert it's null after `join()`. Cheap,
generically applicable to any test spawning a raw `Thread`.

Test evidence: `mvn test -Dtest='Presence*,SecurityRulesTest,Network*'` -
216 tests, 0 failures (up from the pre-existing 146 presence+security
baseline, difference is network module tests pulled in by the exception
move).
