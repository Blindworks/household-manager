---
name: feedback-day-relative-text-windows
description: Any scheduler/announce window whose output text says "today"/"tomorrow" must never cross midnight, regardless of how dedup is keyed
metadata:
  type: feedback
---

A scheduled check window (e.g. "announce within 60 minutes of the configured time") must never
be allowed to cross midnight if the text it produces is day-relative (German "morgen"/"heute",
English "tomorrow"/"today"). Discovered in `WasteReminderService`
(`backend/src/main/java/com/household/manager/service/WasteReminderService.java`): a reminder
configured for 23:30 with a naive wrap-around window (23:30–00:30) fired once correctly at
23:35, then fired again at 00:05 the next day — `collectionService.today()` had rolled forward,
the per-day dedup marker no longer matched, and the second announcement named the wrong day
because "morgen" had silently changed meaning.

**Why:** re-keying the dedup marker on the new day does NOT fix this — at 00:05 the target day
is still unmarked, so a wrap-around window still double-fires. The bug is not in the
deduplication logic at all; it's that the window's validity is bounded by clock time while the
text's correctness is bounded by calendar date. Any window that can straddle midnight can produce
a *correct* window-membership check and a *false* sentence at the same time.

**How to apply:** when building a `Clock`-driven time-of-day window (start + duration) that
feeds a day-relative sentence, clamp the window end to `LocalTime.MAX` instead of letting
`LocalTime.plus(...)` wrap past midnight:
```java
LocalTime end = start.plus(window);
if (end.isBefore(start)) {
    end = LocalTime.MAX; // never wrap — clamp instead
}
return !now.isBefore(start) && now.isBefore(end);
```
This makes a late-configured window shorter rather than wrapping, which fails soft (the user
still gets the announcement, just in a smaller window) instead of failing wrong. Prefer this
over rejecting late configuration values at the settings layer — clamping needs no extra
validation UI/error path and still serves the user. Write a regression test with two clocks:
one just after the configured start on day D (must announce), one just after midnight on D+1
with `today()` stubbed to D+1 (must NOT announce) — a same-day-only test suite will not catch
this class of bug.

Related: [[entity-state-layer]] (Clock injection discipline generally, Europe/Berlin pinning).
