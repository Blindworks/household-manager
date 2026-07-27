---
name: double-validation-nan-pitfalls
description: Java double comparisons with NaN are always false; how that breaks naive range validation and when an explicit Double.isFinite guard is load-bearing vs. redundant
metadata:
  type: feedback
---

Every comparison (`<`, `>`, `<=`, `>=`) involving `Double.NaN` evaluates to `false` in Java. This silently defeats validation written as a single one-sided check.

**Real bug (Tractive home-settings API, `TractiveHomeSettingsController`, found by code review probing the live controller with real HTTP requests, not by reading):** `Math.abs(settings.homeLatitude()) > 90` was meant to reject out-of-range latitudes. For `NaN`, `Math.abs(NaN) > 90` is `false`, so a `NaN` latitude (Jackson happily deserializes the JSON string `"NaN"` into a `Double` field — no special config needed) sailed through with HTTP 200 and got persisted. `TractiveHomeSettingsService.coordinate()` then treats a stored `NaN` as `!Double.isFinite` → `null`, silently disabling the whole home-tracking feature with zero error anywhere. Fix: `!Double.isFinite(x) || Math.abs(x) > limit`.

**Counter-check surprise, worth remembering before adding an isFinite guard reflexively:** for a *two-sided* range check already bounded above by a finite constant (`meters > 0 && meters <= MAX_RADIUS_METERS` where `MAX_RADIUS_METERS` is a normal finite double), `Double.isFinite` turned out to be **logically redundant** — I verified this by deleting it and re-running the test suite, and `aNaNRadiusIsRejected` still passed. Reasoning: `NaN > 0` is already `false` (so the AND short-circuits to false → rejected), and `Infinity <= 100_000.0` is already `false` (same result). A finite upper bound already excludes both NaN and Infinity as a side effect of how those comparisons behave. It's still fine to keep the explicit `isFinite` call for readability/self-documentation, but don't assume removing it will make a test fail — check the actual comparison shape first. The lesson generalizes: **`isFinite` is only load-bearing when a check is one-sided** (e.g., `abs(x) > limit` with nothing forcing a false on the low end) or when there's no companion finite upper bound to fall back on.

Related: `[[security-matcher-order-testing]]` — same "reviewer probed with real requests, not just reading" pattern; both bugs were caught by exercising the running controller, not by code review alone.
