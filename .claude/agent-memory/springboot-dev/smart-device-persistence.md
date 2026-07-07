---
name: smart-device-persistence
description: Pattern for persisting discovered Tapo/smart devices into smart_devices table without clobbering user data
metadata:
  type: project
---

Feature branch `feature/smart-device-persistence` adds DB persistence for smart-home
device IPs so they survive restarts (previously only in-memory caches / static config).

## Architecture
- `SmartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType, String)` is the
  lookup key (unique combo). Used both to *read* cached IP (in `resolveIpAddress`) and to
  *write* discovered devices (`persistDiscoveredDevice` in `TapoDeviceService`).
- Discovery flow (`TapoDeviceService.discoverLocalDevices()`) now upserts every discovered
  `TapoDiscoveryDevice` into `smart_devices` after establishing the local connection.
- Metadata column is a JSON TEXT blob merged via a `Map<String,Object>` — must merge, not
  overwrite, so unrelated keys (e.g. `deviceMac` written by `SmartDeviceService`'s cloud
  scan) survive. Centralized in a private `readMetadata(String)` helper that returns
  `Collections.emptyMap()` on parse failure (logged at debug level) — do not silently
  swallow with a bare `catch { return null; }`, and don't deserialize into raw `Map.class`
  (unchecked-cast warning); use `new TypeReference<Map<String,Object>>(){}` instead.

## Rules that came from explicit product requirements (not just code cleanliness)
- Never overwrite `deviceName` if it is already set — users rename devices via the
  update endpoint and discovery must not clobber that. Only set a name for brand-new
  rows, with fallback chain: nickname -> model -> deviceId -> literal "Tapo Device".
- `SmartDevice.deviceName` is `nullable = false` at the JPA/DB level, so the fallback
  chain must never produce null/blank for new rows.

## TDD flow that worked well here
1. Task instructions specified exact test bodies to paste in first (RED), including an
   updated `newService()` factory (had to set email/password on `TapoProperties` because
   discovery now validates credentials implicitly through the persistence path).
2. Verified RED with `mvn test -Dtest=TapoDeviceServicePersistenceTest -q` — saw
   `Wanted but not invoked: smartDeviceRepository.save(...)` exactly as expected before
   any implementation existed.
3. Implemented, reran same test class for GREEN, then ran full `mvn test` — only the 3
   known environment failures appeared (`HouseholdManagerApplicationTests.contextLoads`,
   `HealthControllerTest` x2) due to missing local test DB / MariaDB auth, matching prior
   notes in [[backend-jdk21-build]] (see user global memory). Everything else green
   (84 tests, 0 failures, 3 pre-existing env errors).

## Review follow-up folded into same task
Task included fixing prior review findings on `readAuthProtocol` (from an earlier
commit) as part of the same commit rather than a separate one — centralizing metadata
parsing was the fix. Worth checking if a task description says "fix review findings
from Task N" — implement them together, don't defer.

## Task 3: self-healing IP + removing the Tapo cloud control fallback
- Tapo's Cloud API (V2 passthrough) can *list* devices but never actually control them
  locally-paired Tapo devices — `setDevicePowered`/`getDeviceInfo`/`getEnergyUsage` via
  cloud always return `-20571 Device is offline`. Confirmed dead code; removed entirely
  from `turnOn`/`turnOff`/`getStatus`/`getEnergyUsage`. `tapoCloudService` is still a
  required collaborator for `discoverCloudDevices`, `decodeAlias`, `buildMetadata`, and
  `TapoDeviceState.fromLocal(..., tapoCloudService)` — don't remove the field/import.
- New pattern: `executeLocalWithRediscovery` wraps `executeLocalWithFallback` (the
  KLAP/AES dual-protocol try). On failure (or missing IP), do exactly ONE
  `rediscoverIp()` (clears ip cache + local connections for the device, calls
  `discoverLocalDevices()`, which upserts DB via `persistDiscoveredDevice`), then retry
  once with the freshly discovered IP. If rediscovery yields nothing or the same stale
  IP, throw a clear `TapoException` mentioning "erneuter Suche" (tests assert on that
  substring) — no silent cloud fallback.
- **Pre-existing cache-key bug fixed here**: `localConnectionCache` keys are
  `deviceId + ":" + protocol.name()` (see `getOrCreateLocalConnection`), but
  `executeLocalWithFallback`'s catch blocks and `clearLocalConnection` were evicting
  with the bare `deviceId` key — a no-op eviction that never actually cleared stale
  connections. Fixed by adding a `removeLocalConnections(deviceId)` helper that loops
  `TapoAuthProtocol.values()` and removes both protocol-suffixed keys; call this from
  `clearLocalConnection` and from the new `rediscoverIp`. In `executeLocalWithFallback`
  itself, use the specific `deviceId + ":" + preferred.name()` / `...alternative.name()`
  keys in each catch block instead of the bare id.
- Test file had accumulated fully-qualified references (`org.mockito.Mockito.verify`,
  `java.util.List.of`, etc.) from earlier tasks instead of static imports — cleaning
  these up was an explicit sub-step of this task, not optional polish. Worth checking
  test files for this drift proactively when extending them.

## Correctness-review fix: upsertTapoDevice clobbering live state (post-approval)
- Found in final review of this branch: `SmartDeviceService.upsertTapoDevice` used to
  unconditionally `setPoweredOn(false)` then try to re-derive via `getStatus`, and
  unconditionally `setOnline(true)` with a comment "Cloud status field is unreliable".
  Both survived a failed `getStatus` probe uncorrected (catch block only logged at
  debug) — so a rescan of a real device could downgrade an actually-ON device to shown
  OFF, and mark an unreachable cloud-only device as a "ghost online" (online=true,
  poweredOn=false).
- Fix: `device.setOnline(localDevice != null)` (only trust reachability established by
  *this scan's* local discovery pass); removed the `setPoweredOn(false)` line entirely
  (row is either freshly upserted by `discoverLocalDevices()` with accurate live state,
  or brand-new where the boolean already defaults false). On `getStatus` failure, leave
  both fields exactly as set above — do not force any value. This deliberately differs
  from `refreshKasaDeviceState`/`refreshMerossDeviceState`, which do `setOnline(false)`
  in their catch blocks — those don't have a prior "was it just seen locally this scan"
  signal to fall back on, `upsertTapoDevice` does.
- Test approach: `SmartDeviceServiceTest` constructs `SmartDeviceService` directly with
  Mockito mocks (it's `@RequiredArgsConstructor`, no Spring context needed) — much
  faster than `@SpringBootTest`. Key gotcha: `scanTapoDevices()` calls
  `discoverLocalTapoDevices()` which maps `TapoDiscoveryDevice::deviceId` — the mock's
  discovery device deviceId must match the cloud device's deviceId for `localDevice`
  to be non-null in the "found locally" test case, and the local-discovery list must be
  empty (not just no matching id) for the "cloud-only, unreachable" case.
