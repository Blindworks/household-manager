---
name: tplink-modern-device-probe
description: Manual diagnostic test pattern for probing modern TP-Link (Tapo protocol) devices without Spring context; TAPO_EMAIL/TAPO_PASSWORD not present in this dev environment
metadata:
  type: project
---

Task 1 of the "TP-Link Leuchtmittel" plan (branch `feature/tplink-leuchtmittel`) added
`backend/src/test/java/com/household/manager/tapo/TapoLocalProbeManualTest.java`: a
`@EnabledIfSystemProperty(named = "probeEnabled", matches = "true")`-gated JUnit 5 test,
no Spring context, that drives `TapoDeviceFactory` directly (KLAP tried first, falls back
to AES) against a device IP and prints a redacted `get_device_info` response.

**Why:** the user's light at 192.168.1.114 has port 9999 (legacy Kasa) closed but port 80
open and answers `POST /app` with `{"error_code":1003}` — it speaks the same modern
KLAP/AES-securePassthrough protocol the Tapo integration already implements, not the
legacy Kasa protocol. Before building a real feature, the plan wanted the actual handshake
and the actual `get_device_info` shape proven, not assumed.

**Reading credentials without Spring is not a plain `Properties.load` away:**
`application.properties` stores Tapo secrets as `tapo.email=${TAPO_EMAIL:}` — a Spring
placeholder. A raw `java.util.Properties` load returns that placeholder text verbatim, not
the resolved value. The test has to regex-match `^\$\{([A-Za-z0-9_]+)(:(.*))?}$` and resolve
from `System.getenv(...)` itself, falling back to the `:default` portion. Reusable for any
future manual/no-Spring probe that needs a secret from `application.properties`.

**Update 2026-08-18 (same day, later):** the missing-credentials finding above was only a
snapshot of that moment — the user subsequently made `TAPO_EMAIL`/`TAPO_PASSWORD` available
and a real KLAP handshake against 192.168.1.114 succeeded. The device is a Tapo L530 bulb
(nickname Base64 `Rmx1cg==` = "Flur"). Real (redacted) `get_device_info` fields relevant to
capability derivation: `brightness`, `hue`, `saturation`, `color_temp` (Kelvin, can be `0`
while the bulb is in pure-colour mode — presence of the field is what matters, not the
value) and `color_temp_range` (e.g. `[2500, 6500]`). A plug-type device (`SMART.TAPOPLUG`)
has none of these fields. This result is the fixture basis for [[tplink-capability-mapper]]
and will also drive Task 4 (`set_device_info` field names for actually setting light state —
still needs its own verification, this probe only read `get_device_info`, it never issued a
`set_device_info` call).

Run command that works (JDK 21 required, see [[backend-jdk21-build]] equivalent — set
`JAVA_HOME` to jdk-21.0.10 first):
```
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=TapoLocalProbeManualTest -DfailIfNoTests=false -DprobeEnabled=true -Dprobe.ip=192.168.1.114
```
Maven's `-Dtest=` single-class selection worked fine here, no `-Dsurefire.failIfNoSpecifiedTests=false` needed.
