# Blink Bewegungsmeldung + Tablet-Schalten Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bewegungen der Blink-Kameras als Flow-Ereignis + „Letzte Bewegung"-Anzeige; Scharf/Unscharf vom Wandtablet (Unscharf mit Bestätigungsdialog); Thumbnail-Platzhalter.

**Architecture:** Der Sidecar-Poller bekommt einen zweiten, unabhängigen Verbraucher (MotionWatcher, eigener 30-s-Takt, Hochwassermarke je Kamera, Webhook erst-dann-Marke). Das Backend nimmt den Webhook mit SERVICE-Authority an, feuert `event.blink_<cameraId>_motion` und merkt sich `lastMotionAt` im Speicher; `GET /cameras` wird angereichert. Die KIOSK-Sperre fürs Schalten wird bewusst revidiert (Whitelist + Test-Umkehr), die Tablet-Ansicht bekommt Gruppierung, Schalter und Bestätigungsdialog. Spec: `docs/superpowers/specs/2026-08-27-blink-bewegung-und-tablet-schalten-design.md`.

**Tech Stack:** Python/FastAPI/blinkpy 0.25.9 (Sidecar), Spring Boot 3.4/Java 21, Angular 19.

**Bestandsregeln (gelten unverändert):**
- Alle blinkpy-Spezifika NUR in `blink-vision/app/blink_client.py`.
- Backend-Build: `$env:JAVA_HOME` auf JDK 21; `contextLoads` ist vorbestehend rot.
- Frontend-Baseline: 3 Fails (App/Hero) + SmartDeviceList-Flake.
- Der Gesichtserkennungs-Pfad (`fetch_new_clips`, `ClipDedupe`, `Poller._process_clip`) wird NICHT angefasst — daran hängt der Auto-Unlock-Flow.
- Mutationsproben an sicherheitsrelevanten Tests (etabliertes Vorgehen: gezielt kaputt machen, genau den zuständigen Test rot sehen, zurücksetzen, berichten).

**Bewusste Kostenentscheidung:** Der MotionWatcher macht einen EIGENEN Manifest-Durchlauf alle 30 s (`MOTION_POLL_SECONDS`), statt den 10-s-Durchlauf der Gesichtserkennung mitzubenutzen. Grund: `fetch_new_clips` umzubauen wäre ein Eingriff in den sicherheitskritischen Pfad; +1 Cloud-Abruf je 30 s ist der Preis, die Latenzvorgabe (15–60 s) hält das ein.

---

### Task 1: Sidecar — `manifest_snapshot()` in `blink_client.py`

Liefert die Manifest-Metadaten ALLER Kameras mit aufgelöster stabiler `camera_id` — die Datenquelle des MotionWatchers. Kein Download, kein Dedupe-Kontakt.

**Files:**
- Modify: `blink-vision/app/blink_client.py`
- Test: `blink-vision/tests/test_camera_dashboard.py` (ergänzen)

- [ ] **Step 1: Failing Tests schreiben**

In `tests/test_camera_dashboard.py` ergänzen (Fakes nach dem Muster der vorhandenen `_FakeBlink`/`SimpleNamespace`-Fixtures dieser Datei — die dortigen Helfer wiederverwenden, wo sie passen):

```python
def _manifest_item(item_id, name, created_at):
    return SimpleNamespace(id=item_id, name=name, created_at=created_at, size=1)


def _snapshot_blink():
    """Zwei Sync-Module: 'Zuhause' (Local Storage, 2 Kameras) und 'Garage' (ohne)."""
    cam_front = _cam(camera_id="1")
    cam_living = _cam(camera_id="2")
    home = SimpleNamespace(
        cameras={"Frontdoor": cam_front, "Wohnzimmer": cam_living},
        arm=True, local_storage=True,
        _local_storage={"manifest": [
            _manifest_item(10, "Frontdoor", datetime(2026, 8, 27, 10, 0, 0)),
            _manifest_item(11, "Wohnzimmer", datetime(2026, 8, 27, 11, 0, 0)),
            _manifest_item(12, "Frontdoor", datetime(2026, 8, 27, 12, 0, 0)),
        ]})
    home.refresh = _async_noop
    garage = SimpleNamespace(cameras={"Aussen": _cam(camera_id="3")},
                             arm=False, local_storage=False)
    return SimpleNamespace(sync={"Zuhause": home, "Garage": garage})


def test_manifest_snapshot_resolves_camera_ids_and_sorts_newest_first():
    client = _logged_in_client(_snapshot_blink())
    snapshot = asyncio.run(client.manifest_snapshot())
    assert [entry["clipId"] for entry in snapshot] == ["12", "11", "10"]
    assert snapshot[0] == {
        "cameraId": "1",
        "cameraName": "Frontdoor",
        "clipId": "12",
        "createdAt": "2026-08-27T12:00:00",
    }


def test_manifest_snapshot_skips_clips_of_unknown_cameras():
    blink = _snapshot_blink()
    blink.sync["Zuhause"]._local_storage["manifest"].append(
        _manifest_item(99, "GeloeschteKamera", datetime(2026, 8, 27, 13, 0, 0)))
    client = _logged_in_client(blink)
    snapshot = asyncio.run(client.manifest_snapshot())
    assert all(entry["clipId"] != "99" for entry in snapshot)


def test_manifest_snapshot_requires_login():
    client = BlinkClient("./data", "")
    with pytest.raises(BlinkNotLoggedInError):
        asyncio.run(client.manifest_snapshot())
```

Falls es in der Datei noch keinen Helfer `_logged_in_client(blink)`/`_async_noop` gibt, so anlegen (bzw. den vorhandenen äquivalenten Helfer nutzen — die Datei hat für die Warteschleifen-Tests bereits ein Muster, eingeloggte Clients mit Fake-`_blink` zu bauen; dem folgen):

```python
async def _async_noop(*args, **kwargs):
    return None


def _logged_in_client(blink) -> BlinkClient:
    client = BlinkClient("./data", "")
    client._blink = blink
    client._pending_2fa = False
    return client
```

- [ ] **Step 2: Tests laufen lassen — FAIL erwartet**

```powershell
Set-Location blink-vision; .venv\Scripts\python -m pytest tests/test_camera_dashboard.py -v
```

Expected: FAIL (`manifest_snapshot` existiert nicht).

- [ ] **Step 3: Implementieren**

In `BlinkClient` (nach `fetch_clip`):

```python
    async def manifest_snapshot(self) -> list[dict]:
        """Manifest-Metadaten ALLER Kameras, neueste zuerst — Datenquelle des
        Bewegungs-Waechters. Liest nur Metadaten (kein Download) und fasst den
        Dedupe-Store der Gesichtserkennung nicht an. Die Kamera wird ueber ihre
        stabile camera_id ausgewiesen; Clips von Kameras, die im Sync-Modul
        nicht (mehr) existieren, werden verworfen statt geraten."""
        blink = self._require_login()
        snapshot: list[dict] = []
        for sync in blink.sync.values():
            names_to_ids = {name: str(cam.camera_id) for name, cam in sync.cameras.items()}
            for item in await _manifest_newest_first(sync):
                camera_id = names_to_ids.get(item.name)
                if camera_id is None:
                    continue
                snapshot.append({
                    "cameraId": camera_id,
                    "cameraName": item.name,
                    "clipId": str(item.id),
                    "createdAt": item.created_at.isoformat(),
                })
        return snapshot
```

**Hinweis:** Bei mehreren Sync-Modulen ist die Liste je Modul absteigend sortiert, modulübergreifend nicht global — für den Wächter (Marken je Kamera) ist das gleichgültig. Sollte der erste Test deshalb wackeln (beide Test-Kameras hängen am selben Modul, also nicht relevant), NICHT global sortieren, sondern den Test auf ein Modul beschränken.

- [ ] **Step 4: Tests laufen lassen**

```powershell
.venv\Scripts\python -m pytest tests/ -q
```

Expected: alle PASS (Bestand: 131 + neue).

- [ ] **Step 5: Commit**

```bash
git add blink-vision/app/blink_client.py blink-vision/tests/test_camera_dashboard.py
git commit -m "feat(blink-vision): manifest_snapshot liefert Clip-Metadaten aller Kameras"
```

---

### Task 2: Sidecar — MotionWatcher + Webhook + Poller-Anbindung

**Files:**
- Create: `blink-vision/app/motion.py`
- Modify: `blink-vision/app/backend_client.py`
- Modify: `blink-vision/app/config.py`
- Modify: `blink-vision/app/poller.py`
- Test: `blink-vision/tests/test_motion_watcher.py`

- [ ] **Step 1: Failing Tests schreiben**

`blink-vision/tests/test_motion_watcher.py`:

```python
"""Bewegungs-Waechter: Hochwassermarken, Erststart ohne Feuern, Webhook-Wiederholung."""
import asyncio

from app.motion import MotionWatcher


def _entry(camera_id, clip_id, created_at):
    return {"cameraId": camera_id, "cameraName": f"Cam{camera_id}",
            "clipId": clip_id, "createdAt": created_at}


class _FakeSource:
    def __init__(self):
        self.snapshots: list[list[dict]] = []

    async def manifest_snapshot(self):
        return self.snapshots.pop(0) if self.snapshots else []


class _FakeSink:
    def __init__(self, fail_times: int = 0):
        self.calls: list[list[dict]] = []
        self._fail_times = fail_times

    async def post_motion(self, events):
        if self._fail_times > 0:
            self._fail_times -= 1
            raise RuntimeError("backend down")
        self.calls.append(events)


def _run(watcher):
    asyncio.run(watcher.check())


def test_first_run_initializes_marks_without_firing():
    source, sink = _FakeSource(), _FakeSink()
    source.snapshots = [[_entry("1", "12", "2026-08-27T12:00:00"),
                         _entry("1", "10", "2026-08-27T10:00:00")]]
    watcher = MotionWatcher(source, sink)
    _run(watcher)
    assert sink.calls == []


def test_new_clip_fires_exactly_once():
    source, sink = _FakeSource(), _FakeSink()
    source.snapshots = [
        [_entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "12", "2026-08-27T12:00:00"), _entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "12", "2026-08-27T12:00:00"), _entry("1", "10", "2026-08-27T10:00:00")],
    ]
    watcher = MotionWatcher(source, sink)
    _run(watcher); _run(watcher); _run(watcher)
    assert len(sink.calls) == 1
    assert sink.calls[0] == [_entry("1", "12", "2026-08-27T12:00:00")]


def test_failed_webhook_keeps_mark_and_retries_next_cycle():
    source, sink = _FakeSource(), _FakeSink(fail_times=1)
    source.snapshots = [
        [_entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "12", "2026-08-27T12:00:00")],
        [_entry("1", "12", "2026-08-27T12:00:00")],
    ]
    watcher = MotionWatcher(source, sink)
    _run(watcher)          # initialisiert
    _run(watcher)          # Webhook scheitert -> Marke bleibt
    _run(watcher)          # Wiederholung -> Erfolg
    assert len(sink.calls) == 1
    assert sink.calls[0][0]["clipId"] == "12"


def test_cameras_are_tracked_independently():
    source, sink = _FakeSource(), _FakeSink()
    source.snapshots = [
        [_entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("2", "20", "2026-08-27T12:00:00"), _entry("1", "10", "2026-08-27T10:00:00")],
    ]
    watcher = MotionWatcher(source, sink)
    _run(watcher); _run(watcher)
    # Kamera 2 taucht erstmals auf UND hat einen Clip: erste Sichtung einer Kamera
    # initialisiert nur deren Marke, feuert nicht (gleiches Prinzip wie der Erststart).
    assert sink.calls == []


def test_snapshot_error_does_not_raise():
    class _BrokenSource:
        async def manifest_snapshot(self):
            raise RuntimeError("cloud down")
    watcher = MotionWatcher(_BrokenSource(), _FakeSink())
    _run(watcher)  # darf nicht werfen
```

- [ ] **Step 2: Tests laufen lassen — FAIL erwartet**

```powershell
.venv\Scripts\python -m pytest tests/test_motion_watcher.py -v
```

- [ ] **Step 3: Implementieren**

`blink-vision/app/motion.py`:

```python
"""Bewegungs-Waechter: neue Local-Storage-Clips -> Motion-Webhook ans Backend.

Zweiter, unabhaengiger Verbraucher des Manifests neben der Gesichtserkennung.
Fasst weder deren Dedupe-Store noch deren Download-Pfad an und laeuft in einem
eigenen, langsameren Takt (MOTION_POLL_SECONDS) — ein Umbau des 10-s-Durchlaufs
der Erkennung waere ein Eingriff in den Pfad, an dem der Auto-Unlock haengt.
"""
import logging

log = logging.getLogger(__name__)


class MotionWatcher:
    """Hochwassermarke je Kamera (created_at, im Speicher).

    Erste Sichtung einer Kamera setzt nur die Marke, ohne zu feuern — sonst
    ergoesse sich beim Start der komplette Alt-Bestand des Manifests als
    Meldeschwall. Die Marke wird erst NACH erfolgreichem Webhook vorgezogen:
    ist das Backend gerade nicht erreichbar (Deploy), wird dieselbe Bewegung
    im naechsten Zyklus erneut gemeldet statt verloren zu gehen.
    """

    def __init__(self, source, sink):
        self._source = source          # hat: async manifest_snapshot() -> list[dict]
        self._sink = sink              # hat: async post_motion(events) -> None
        self._marks: dict[str, str] = {}   # cameraId -> hoechstes bekanntes createdAt (ISO)

    async def check(self) -> None:
        """Ein Durchlauf; wirft nie (der Poll-Loop darf nicht reissen)."""
        try:
            snapshot = await self._source.manifest_snapshot()
        except Exception as ex:
            log.warning("Bewegungs-Check: Manifest nicht lesbar: %s", ex)
            return

        events: list[dict] = []
        pending_marks: dict[str, str] = {}
        for entry in snapshot:
            camera_id = entry["cameraId"]
            created_at = entry["createdAt"]
            mark = self._marks.get(camera_id)
            if mark is None:
                # Erste Sichtung dieser Kamera: Marke setzen, nicht feuern.
                pending_marks[camera_id] = max(created_at, pending_marks.get(camera_id, ""))
                continue
            if created_at > mark:
                events.append(entry)
                pending_marks[camera_id] = max(created_at, pending_marks.get(camera_id, ""))

        # Erstsichtungs-Marken sofort uebernehmen (kein Webhook noetig).
        for camera_id, created_at in list(pending_marks.items()):
            if self._marks.get(camera_id) is None and not any(
                    e["cameraId"] == camera_id for e in events):
                self._marks[camera_id] = created_at
                del pending_marks[camera_id]

        if not events:
            return
        try:
            await self._sink.post_motion(events)
        except Exception as ex:
            log.warning("Motion-Webhook fehlgeschlagen, naechster Zyklus wiederholt: %s", ex)
            return
        self._marks.update(pending_marks)
```

`backend_client.py` — ergänzen (der bestehende `API_PREFIX` bleibt für Vision; Motion hat einen eigenen Pfad):

```python
BLINK_API_PREFIX = "/api/v1/blink"


def blink_url(path: str) -> str:
    """Backend-URL fuer einen Blink-Endpunkt (gleicher /api-Kontextpfad wie Vision)."""
    return f"{config.BACKEND_URL.rstrip('/')}{BLINK_API_PREFIX}{path}"


async def post_motion(events: list[dict]) -> None:
    async with httpx.AsyncClient(timeout=30, headers=_headers()) as client:
        response = await client.post(blink_url("/motion"), json=events)
        response.raise_for_status()
```

`config.py` — ergänzen:

```python
MOTION_POLL_SECONDS = int(os.environ.get("MOTION_POLL_SECONDS", "30"))
```

`poller.py` — MotionWatcher einhängen (Muster des Heartbeat-Timers in `run_forever`; Import oben `from app.motion import MotionWatcher`):

Im `__init__` ergänzen:

```python
        # sink ist das backend_client-Modul selbst (hat post_motion) — im Test ersetzbar.
        self._motion = MotionWatcher(blink_client, backend_client)
```

In `run_forever` (nach dem Heartbeat-Block, vor dem Erkennungs-`try`; `motion_due = 0.0` neben `heartbeat_due` initialisieren):

```python
            # Bewegungs-Check in eigenem, langsamerem Takt und eigener Absicherung:
            # ein Fehler hier darf die Gesichtserkennung nicht aussetzen (und umgekehrt).
            if self._blink.logged_in and time.monotonic() >= motion_due:
                await self._motion.check()   # wirft nie (eigene Absicherung im Watcher)
                motion_due = time.monotonic() + config.MOTION_POLL_SECONDS
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
.venv\Scripts\python -m pytest tests/ -q
```

Expected: alle PASS.

- [ ] **Step 5: Commit**

```bash
git add blink-vision/app/motion.py blink-vision/app/backend_client.py blink-vision/app/config.py blink-vision/app/poller.py blink-vision/tests/test_motion_watcher.py
git commit -m "feat(blink-vision): Bewegungs-Waechter meldet neue Clips per Webhook"
```

---

### Task 3: Backend — `BlinkMotionService` (Ereignis + „letzte Bewegung")

**Files:**
- Create: `backend/src/main/java/com/household/manager/blink/BlinkMotionService.java`
- Test: `backend/src/test/java/com/household/manager/blink/BlinkMotionServiceTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.blink;

import com.household.manager.blink.BlinkMotionService.MotionReport;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BlinkMotionServiceTest {

    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private BlinkMotionService service;

    private static final MotionReport MOTION =
            new MotionReport("123", "Frontdoor", "42", "2026-08-27T12:00:00");

    @BeforeEach
    void setUp() {
        service = new BlinkMotionService(entityStateService);
    }

    @Test
    void feuertEreignisJeBewegung() {
        service.processMotions(List.of(MOTION));

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        EntityStateUpdate event = captor.getValue();
        assertThat(event.entityId()).isEqualTo("event.blink_123_motion");
        assertThat(event.domain()).isEqualTo(EntityDomain.EVENT);
        assertThat(event.source()).isEqualTo(EntitySource.BLINK);
        assertThat(event.state()).isEqualTo("motion");
        assertThat(event.friendlyName()).isEqualTo("Frontdoor Bewegung");
        assertThat(event.attributes())
                .containsEntry("cameraName", "Frontdoor")
                .containsEntry("clipId", "42")
                .containsEntry("createdAt", "2026-08-27T12:00:00");
    }

    @Test
    void merktSichLetzteBewegungJeKamera() {
        service.processMotions(List.of(MOTION));

        var last = service.lastMotion("123").orElseThrow();
        assertThat(last.createdAt()).isEqualTo("2026-08-27T12:00:00");
        assertThat(last.clipId()).isEqualTo("42");
        assertThat(service.lastMotion("999")).isEmpty();
    }

    @Test
    void neuereBewegungUeberschreibtAeltere() {
        service.processMotions(List.of(MOTION));
        service.processMotions(List.of(
                new MotionReport("123", "Frontdoor", "43", "2026-08-27T13:00:00")));

        assertThat(service.lastMotion("123").orElseThrow().clipId()).isEqualTo("43");
    }

    @Test
    void eventFehlerVerhindertDasMerkenNicht() {
        doThrow(new RuntimeException("boom")).when(entityStateService).reportEvent(any());

        service.processMotions(List.of(MOTION));

        assertThat(service.lastMotion("123")).isPresent();
    }
}
```

- [ ] **Step 2: Test laufen lassen — Compile-FAIL erwartet**

```powershell
Set-Location backend; mvn test -Dtest=BlinkMotionServiceTest
```

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.blink;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nimmt Bewegungsmeldungen des blink-vision-Sidecars entgegen: je Bewegung ein
 * Ereignis {@code event.blink_<cameraId>_motion} (Flows: „Bewegung + Abwesend
 * → Push") und die letzte Bewegung je Kamera fuer die Dashboard-Anzeige.
 *
 * Die letzte Bewegung lebt NUR im Speicher (Muster NetworkDeviceStatusMonitor):
 * ueberlebt keinen Neustart — nach einem Deploy ist die Anzeige leer, bis die
 * naechste Bewegung kommt. Bewusste Grenze, keine Tabelle wert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlinkMotionService {

    private final EntityStateService entityStateService;

    private final Map<String, LastMotion> lastMotions = new ConcurrentHashMap<>();

    /** Eine Bewegung laut Sidecar-Webhook (createdAt als ISO-String durchgereicht). */
    public record MotionReport(String cameraId, String cameraName, String clipId, String createdAt) {}

    /** Letzte bekannte Bewegung einer Kamera (fuer die Anreicherung von GET /cameras). */
    public record LastMotion(String createdAt, String clipId) {}

    public void processMotions(List<MotionReport> motions) {
        for (MotionReport motion : motions) {
            fireEventSafely(motion);
            lastMotions.put(motion.cameraId(),
                    new LastMotion(motion.createdAt(), motion.clipId()));
        }
    }

    public Optional<LastMotion> lastMotion(String cameraId) {
        return Optional.ofNullable(lastMotions.get(cameraId));
    }

    /** Muster VisionRecognitionService.fireEventSafely: ein Event-Fehler darf
     *  weder die uebrigen Meldungen noch das Merken mitreissen. */
    private void fireEventSafely(MotionReport motion) {
        try {
            entityStateService.reportEvent(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.EVENT, EntitySource.BLINK,
                            motion.cameraId(), "motion"))
                    .domain(EntityDomain.EVENT)
                    .source(EntitySource.BLINK)
                    .sourceRef(motion.cameraId())
                    .friendlyName(motion.cameraName() + " Bewegung")
                    .state("motion")
                    .attributes(Map.of(
                            "cameraName", motion.cameraName(),
                            "clipId", motion.clipId(),
                            "createdAt", motion.createdAt()))
                    .build());
        } catch (Exception ex) {
            log.warn("Bewegungs-Event fuer Kamera {} nicht gefeuert: {}",
                    motion.cameraId(), ex.getMessage());
        }
    }
}
```

**Prüfpunkt für den Implementierer:** Verifiziere mit einem Blick in `EntityIds.build`, dass `build(EVENT, BLINK, "123", "motion")` wirklich `event.blink_123_motion` ergibt (Muster `VisionRecognitionService.baseUpdate`). Weicht die Signatur ab, den Aufruf anpassen — die Entity-Id im Test ist der Vertrag.

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest=BlinkMotionServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/blink/BlinkMotionService.java backend/src/test/java/com/household/manager/blink/BlinkMotionServiceTest.java
git commit -m "feat(blink): Bewegungs-Ereignisse und letzte Bewegung je Kamera"
```

---

### Task 4: Backend — Webhook-Endpunkt + Anreicherung von `GET /cameras`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/BlinkController.java`
- Modify: `backend/src/main/java/com/household/manager/blink/BlinkCameraService.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (SERVICE-Zeile)
- Test: `backend/src/test/java/com/household/manager/blink/BlinkCameraServiceTest.java` (ergänzen)
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (ergänzen)

- [ ] **Step 1: Failing Tests**

`BlinkCameraServiceTest` ergänzen (bestehende Mocks erweitern — der Service bekommt den `BlinkMotionService` als vierte Abhängigkeit; die bestehenden Tests entsprechend auf den neuen Konstruktor umstellen):

```java
    @Test
    void kameralisteWirdUmLetzteBewegungAngereichert() {
        when(client.listCameras(false)).thenReturn(List.of(new BlinkSidecarClient.SidecarCamera(
                "123", "Frontdoor", "doorbell", true, "ok", "Zuhause", true)));
        when(motionService.lastMotion("123")).thenReturn(Optional.of(
                new BlinkMotionService.LastMotion("2026-08-27T12:00:00", "42")));

        List<BlinkCameraService.CameraResponse> cameras = service.listCameras();

        assertThat(cameras).hasSize(1);
        assertThat(cameras.get(0).lastMotionAt()).isEqualTo("2026-08-27T12:00:00");
        assertThat(cameras.get(0).lastMotionClipId()).isEqualTo("42");
        assertThat(cameras.get(0).name()).isEqualTo("Frontdoor");
    }

    @Test
    void kameraOhneBewegungTraegtNullFelder() {
        when(client.listCameras(false)).thenReturn(List.of(new BlinkSidecarClient.SidecarCamera(
                "123", "Frontdoor", "doorbell", true, "ok", "Zuhause", true)));
        when(motionService.lastMotion("123")).thenReturn(Optional.empty());

        assertThat(service.listCameras().get(0).lastMotionAt()).isNull();
    }
```

`SecurityRulesTest` ergänzen (SERVICE-Muster der Vision-Webhooks übernehmen — nachsehen, wie `kioskDarfKeineVision…`/SERVICE-Tests dort formuliert sind, und exakt dem folgen; erwartet wird sinngemäß):

```java
    /** Der Motion-Webhook ist ein Maschinen-Endpunkt (Sidecar mit Service-Token) —
     *  eine Browser-Session kommt nicht ran (Muster /v1/vision/recognitions). */
    @Test
    @WithMockUser(roles = "ADMIN")
    void selbstAdminSessionDarfKeineBewegungMelden() throws Exception {
        mockMvc.perform(post("/v1/blink/motion").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = SecurityConfig.SERVICE_AUTHORITY)
    void serviceTokenDarfBewegungMelden() throws Exception {
        mockMvc.perform(post("/v1/blink/motion").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Tests laufen lassen — FAIL erwartet**

```powershell
mvn test -Dtest="BlinkCameraServiceTest,SecurityRulesTest"
```

- [ ] **Step 3: Implementieren**

`BlinkCameraService` — Abhängigkeit + Antwort-Record + Anreicherung (Rest unverändert):

```java
    private final BlinkMotionService motionService;   // als vierte final-Abhaengigkeit

    /** Kamera fuer das Frontend: Sidecar-Daten plus letzte Bewegung (null = keine bekannt). */
    public record CameraResponse(String cameraId, String name, String type, boolean armed,
                                 String battery, String syncName, boolean syncArmed,
                                 String lastMotionAt, String lastMotionClipId) {}

    public List<CameraResponse> listCameras() {
        return client.listCameras(false).stream().map(this::toResponse).toList();
    }

    private CameraResponse toResponse(SidecarCamera camera) {
        var last = motionService.lastMotion(camera.cameraId()).orElse(null);
        return new CameraResponse(camera.cameraId(), camera.name(), camera.type(),
                camera.armed(), camera.battery(), camera.syncName(), camera.syncArmed(),
                last == null ? null : last.createdAt(),
                last == null ? null : last.clipId());
    }
```

`BlinkController` — Rückgabetyp von `getCameras()` auf `List<BlinkCameraService.CameraResponse>` umstellen (Import ergänzen) und den Webhook aufnehmen:

```java
    /** Maschinen-Endpunkt: Bewegungsmeldungen des Sidecars (SERVICE-Authority). */
    @PostMapping("/motion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportMotion(@RequestBody List<BlinkMotionService.MotionReport> motions) {
        motionService.processMotions(motions);
    }
```

(Controller bekommt `BlinkMotionService` als zweite `final`-Abhängigkeit.)

`SecurityConfig` — die bestehende SERVICE-Zeile erweitern:

```java
                        .requestMatchers(HttpMethod.POST,
                                "/v1/vision/recognitions", "/v1/vision/heartbeat",
                                "/v1/blink/motion").hasAuthority(SERVICE_AUTHORITY)
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest="Blink*,SecurityRulesTest"
```

Expected: alle PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager backend/src/test/java/com/household/manager
git commit -m "feat(blink): Motion-Webhook (SERVICE) und letzte Bewegung in der Kameraliste"
```

---

### Task 5: Backend — KIOSK darf schalten (Revision der Sperre)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Tests UMDREHEN (failing)**

Die bestehenden Tests `kioskDarfKeineBlinkKameraSchalten` und `memberDarfBlinkKamerasSchalten` werden ersetzt durch:

```java
    /**
     * REVISION 2026-08-27 (Spec blink-bewegung-und-tablet-schalten): Das
     * Wandtablet darf die Kameras jetzt in BEIDE Richtungen schalten — auf
     * ausdruecklichen Nutzerwunsch. Der Schutz gegen Versehen ist der
     * Bestaetigungsdialog beim Unscharfschalten in der Tablet-Ansicht
     * (UI-Schutz, Muster confirm_required); eine serverseitige Sperre gegen
     * Fremde vor dem frei zugaenglichen Tablet gibt es damit bewusst nicht mehr.
     * Vorher galt: KIOSK darf nicht schalten (Muster Nuki).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfBlinkKamerasSchalten() throws Exception {
        mockMvc.perform(post("/v1/blink/cameras/123/arm").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/blink/cameras/123/disarm").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/blink/system/Zuhause/arm").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/blink/system/Zuhause/disarm").with(csrf()))
                .andExpect(status().isNotFound());
    }
```

(`memberDarfBlinkKamerasSchalten` entfällt — MEMBER erbt KIOSK über die Rollenhierarchie, der Fall ist damit abgedeckt. `kioskDarfBlinkSchnappschussAusloesen` und `kioskDarfBlinkKamerasLesen` bleiben unverändert.)

- [ ] **Step 2: Tests laufen lassen — der neue Test muss fehlschlagen (403 statt 404)**

```powershell
mvn test -Dtest=SecurityRulesTest
```

- [ ] **Step 3: SecurityConfig — Whitelist erweitern und Kommentar nachziehen**

Die KIOSK-POST-Whitelist um die vier Schaltpfade erweitern und den Blink-Kommentar dort ERSETZEN (der alte behauptete „Scharf/Unscharf faellt bewusst auf anyRequest -> MEMBER durch" — ein Kommentar, der das Gegenteil des Codes sagt, ist schlimmer als keiner):

```java
                        // /v1/blink/cameras/*/snapshot zieht nur ein Standbild.
                        // Scharf/Unscharf ist seit der Revision 2026-08-27 ebenfalls
                        // KIOSK (Nutzerentscheidung, Spec blink-bewegung-und-tablet-
                        // schalten): der Schutz gegen Versehen ist der Bestaetigungs-
                        // dialog der Tablet-Ansicht, nicht mehr der Server.
                        .requestMatchers(HttpMethod.POST, "/v1/switches/*/toggle",
                                "/v1/modes/*/toggle", "/v1/nuki/locks/*/actions",
                                "/v1/auth/password", "/v1/tractive/pets/refresh",
                                "/v1/system/reboot", "/v1/network/speedtest",
                                "/v1/blink/cameras/*/snapshot",
                                "/v1/blink/cameras/*/arm", "/v1/blink/cameras/*/disarm",
                                "/v1/blink/system/*/arm", "/v1/blink/system/*/disarm")
                        .hasRole("KIOSK")
```

- [ ] **Step 4: Tests + Mutationsprobe**

```powershell
mvn test -Dtest=SecurityRulesTest
```

Expected: alle PASS. Mutationsprobe: die vier neuen Pfade wieder aus der Whitelist nehmen → genau `kioskDarfBlinkKamerasSchalten` muss rot werden (403 statt 404). Zurücksetzen, grün verifizieren, Ergebnis berichten.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(blink): KIOSK darf Kameras schalten (bewusste Revision der Sperre)"
```

---

### Task 6: Frontend — Model + „Letzte Bewegung" + Platzhalter auf der Website-Seite

**Files:**
- Modify: `frontend/src/app/models/blink.model.ts`
- Modify: `frontend/src/app/pages/cameras/cameras.component.ts`
- Modify: `frontend/src/app/pages/cameras/cameras.component.html`
- Modify: `frontend/src/app/pages/cameras/cameras.component.scss`
- Test: `frontend/src/app/pages/cameras/cameras.component.spec.ts` (ergänzen)

- [ ] **Step 1: Model erweitern**

`blink.model.ts` — `BlinkCamera` um zwei optionale Felder:

```typescript
  /** Letzte erkannte Bewegung (ISO-Zeitstempel) — null/fehlend, wenn keine bekannt. */
  lastMotionAt?: string | null;
  /** Clip der letzten Bewegung; zusammen mit clipUrl direkt abspielbar. */
  lastMotionClipId?: string | null;
```

- [ ] **Step 2: Failing Tests ergänzen**

In `cameras.component.spec.ts` (das bestehende `DOOR`-Fixture um `lastMotionAt: '2026-08-27T12:00:00', lastMotionClipId: '42'` erweitern):

```typescript
  it('zeigt die letzte Bewegung an und spielt ihren Clip bei Klick', () => {
    blinkService.clipUrl.and.callFake((camId, clipId) => `/api/v1/blink/cameras/${camId}/clips/${clipId}`);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const motion = host.querySelector('.last-motion') as HTMLButtonElement;
    expect(motion).not.toBeNull();
    expect(motion.textContent).toContain('Letzte Bewegung');

    motion.click();
    expect(component.playingClipUrl).toBe('/api/v1/blink/cameras/123/clips/42');
  });

  it('ohne bekannte Bewegung fehlt die Zeile wortlos', () => {
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, lastMotionAt: null, lastMotionClipId: null }]));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.last-motion')).toBeNull();
  });

  it('ein Bildfehler blendet den Platzhalter ein, ein Schnappschuss setzt ihn zurueck', () => {
    fixture.detectChanges();
    component.onThumbnailError('123');
    expect(component.hasThumbnailError('123')).toBeTrue();

    const snapshot$ = new Subject<Blob>();
    blinkService.takeSnapshot.and.returnValue(snapshot$.asObservable());
    component.takeSnapshot(DOOR);
    snapshot$.next(new Blob());
    snapshot$.complete();
    expect(component.hasThumbnailError('123')).toBeFalse();
  });
```

- [ ] **Step 3: Tests laufen lassen — FAIL erwartet**

```bash
cd frontend
npx ng test --watch=false --browsers=ChromeHeadless --include="**/cameras.component.spec.ts"
```

- [ ] **Step 4: Implementieren**

`cameras.component.ts` — ergänzen:

```typescript
  /** Kameras, deren Standbild-Abruf fehlgeschlagen ist (Platzhalter statt kaputtem Bild). */
  private readonly thumbnailErrors = new Set<string>();

  onThumbnailError(cameraId: string): void {
    this.thumbnailErrors.add(cameraId);
  }

  hasThumbnailError(cameraId: string): boolean {
    return this.thumbnailErrors.has(cameraId);
  }

  playLastMotion(camera: BlinkCamera): void {
    if (camera.lastMotionClipId) {
      this.playingClipUrl = this.blinkService.clipUrl(camera.cameraId, camera.lastMotionClipId);
    }
  }
```

In `takeSnapshot` im `next`-Zweig zusätzlich `this.thumbnailErrors.delete(camera.cameraId);` (ein frischer Schnappschuss ist der Weg zum ersten Bild — der Platzhalter muss dann weichen).

`cameras.component.html` — das `<img>` ersetzen durch:

```html
            @if (hasThumbnailError(camera.cameraId)) {
              <div class="thumbnail-placeholder" aria-hidden="true">
                <span class="placeholder-icon">📷</span>
                <span>Noch kein Standbild</span>
              </div>
            } @else {
              <img [src]="thumbnailUrl(camera)" [alt]="camera.name" loading="lazy"
                   (error)="onThumbnailError(camera.cameraId)" />
            }
```

Und in `.camera-meta` (unter Name/Badges) die Bewegungszeile:

```html
            @if (camera.lastMotionAt) {
              <button type="button" class="last-motion" (click)="playLastMotion(camera)">
                Letzte Bewegung: {{ camera.lastMotionAt | date:'dd.MM. HH:mm' }}
              </button>
            }
```

`cameras.component.scss` — ergänzen:

```scss
.thumbnail-placeholder {
  aspect-ratio: 16 / 9;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  background: #0f172a;
  color: #94a3b8;
  font-size: 0.85rem;

  .placeholder-icon {
    font-size: 1.6rem;
  }
}

.last-motion {
  background: none;
  border: none;
  color: #2563eb;
  cursor: pointer;
  padding: 0;
  font-size: 0.85rem;
}
```

- [ ] **Step 5: Tests laufen lassen**

```bash
npx ng test --watch=false --browsers=ChromeHeadless --include="**/cameras.component.spec.ts"
```

Expected: alle PASS (6 alte + 3 neue).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/blink.model.ts frontend/src/app/pages/cameras
git commit -m "feat(blink): letzte Bewegung und Thumbnail-Platzhalter auf /cameras"
```

---

### Task 7: Frontend — Tablet: Schalter, Bestätigungsdialog, Gruppierung, Bewegung, Platzhalter

**Files:**
- Modify: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.ts`
- Modify: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.html`
- Modify: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.scss`
- Test: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.spec.ts`

- [ ] **Step 1: Failing Tests**

Die bestehende Spec wird spürbar umgebaut. Die Tests „enthaelt KEINE Scharf/Unscharf-Steuerung" und die Whitelist werden ERSETZT (mit Revisions-Kommentar); der `BlinkService`-Spy bekommt `setCameraArmed`/`setSystemArmed` dazu. Neue/geänderte Tests:

```typescript
  it('zeigt Schalter fuer Kamera und System (Revision: Tablet darf schalten)', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.camera-arm-toggle')).not.toBeNull();
    expect(host.querySelector('.system-arm-toggle')).not.toBeNull();
  });

  it('scharf schalten geht direkt, ohne Dialog', () => {
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, armed: false }]));
    blinkService.setCameraArmed.and.returnValue(of(void 0));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.camera-arm-toggle') as HTMLButtonElement).click();

    expect(blinkService.setCameraArmed).toHaveBeenCalledWith('123', true);
    expect(fixture.componentInstance.pendingDisarm).toBeNull();
  });

  it('unscharf schalten oeffnet erst den Bestaetigungsdialog', () => {
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.camera-arm-toggle') as HTMLButtonElement).click();

    expect(blinkService.setCameraArmed).not.toHaveBeenCalled();
    expect(fixture.componentInstance.pendingDisarm).not.toBeNull();
  });

  it('der Dialog schaltet nur, wenn die Kamera laut aktueller Liste noch scharf ist', () => {
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.requestDisarm({ kind: 'camera', id: '123', name: 'Frontdoor' });

    // Hintergrund-Refresh hat die Kamera inzwischen unscharf geliefert:
    blinkService.getCameras.and.returnValue(of([{ ...DOOR, armed: false }]));
    component['load'](true);
    blinkService.setCameraArmed.and.returnValue(of(void 0));

    component.confirmDisarm();

    expect(blinkService.setCameraArmed).not.toHaveBeenCalled();
    expect(component.pendingDisarm).toBeNull();
  });

  it('gerenderte Bedienelemente bleiben auf der Whitelist', () => {
    // REVISION 2026-08-27: Schalter sind jetzt erlaubt (Nutzerentscheidung,
    // Spec blink-bewegung-und-tablet-schalten). Die Whitelist bleibt das
    // Schutzprinzip: jedes UNBEKANNTE Bedienelement laesst den Test scheitern.
    fixture.detectChanges();
    const component = fixture.componentInstance;
    component.toggleClips(DOOR);
    fixture.detectChanges();

    const allowed = ['snapshot', 'clips-toggle', 'clip-entry', 'player-close',
                     'camera-arm-toggle', 'system-arm-toggle', 'last-motion',
                     'dialog-cancel', 'dialog-confirm'];
    const content = fixture.nativeElement.querySelector('.camera-groups') as HTMLElement;
    const controls = Array.from(content.querySelectorAll('button, a, input'));
    for (const control of controls) {
      const matches = allowed.some(cls => control.classList.contains(cls));
      expect(matches).withContext(`Unbekanntes Bedienelement: ${control.outerHTML}`).toBeTrue();
    }
    expect(controls.length).toBeGreaterThan(0);
  });
```

(Der bestehende Whitelist-Test wird durch diese Fassung ersetzt; der Suchbereich wechselt vom Shell-Inhalt auf den neuen Gruppen-Container `.camera-groups`. Die Tests für Standbild/Fehlerverhalten bleiben. Zusätzlich die zwei Bewegungs-/Platzhalter-Tests aus Task 6 sinngemäß für die Tablet-Spec übernehmen — gleiche Assertions, `last-motion`/Platzhalter.)

- [ ] **Step 2: Tests laufen lassen — FAIL erwartet**

```bash
npx ng test --watch=false --browsers=ChromeHeadless --include="**/tablet-cameras.component.spec.ts"
```

- [ ] **Step 3: Implementieren**

`tablet-cameras.component.ts` — die Typ-Sperre wird erweitert und der Kommentar ERSETZT:

```typescript
  /**
   * REVISION 2026-08-27 (Spec blink-bewegung-und-tablet-schalten): Das Tablet
   * darf jetzt in beide Richtungen schalten — Nutzerentscheidung. Der Schutz
   * gegen Versehen ist der Bestaetigungsdialog beim Unscharfschalten
   * (Muster confirm_required); die frueher hier begruendete Compiler-Sperre
   * gegen setCameraArmed/setSystemArmed ist damit bewusst aufgehoben.
   */
  private readonly blinkService: Pick<
    BlinkService, 'getCameras' | 'getClips' | 'takeSnapshot' | 'thumbnailUrl'
    | 'clipUrl' | 'setCameraArmed' | 'setSystemArmed'
  > = inject(BlinkService);
```

Gruppierung + Dialog-Logik (Muster `CamerasComponent.groupBySync` bzw. `confirm_required`):

```typescript
export interface CameraGroup {
  syncName: string;
  syncArmed: boolean;
  cameras: BlinkCamera[];
}

/** Ziel eines angefragten Unscharfschaltens (Kamera oder ganzes Sync-Modul). */
export interface DisarmRequest {
  kind: 'camera' | 'system';
  id: string;       // cameraId bzw. syncName
  name: string;     // Anzeigename fuer den Dialogtext
}
```

Felder/Methoden in der Komponente:

```typescript
  groups: CameraGroup[] = [];
  pendingDisarm: DisarmRequest | null = null;
  private readonly armBusy = new Set<string>();

  isArmBusy(key: string): boolean {
    return this.armBusy.has(key);
  }

  toggleCamera(camera: BlinkCamera): void {
    if (camera.armed) {
      this.requestDisarm({ kind: 'camera', id: camera.cameraId, name: camera.name });
    } else {
      this.armCamera(camera.cameraId, true);
    }
  }

  toggleSystem(group: CameraGroup): void {
    if (group.syncArmed) {
      this.requestDisarm({ kind: 'system', id: group.syncName, name: group.syncName });
    } else {
      this.armSystem(group.syncName, true);
    }
  }

  requestDisarm(request: DisarmRequest): void {
    this.pendingDisarm = request;
  }

  cancelDisarm(): void {
    this.pendingDisarm = null;
  }

  /**
   * Vor dem Schalten wird das Ziel aus der AKTUELLEN Liste neu aufgeloest und
   * nur fortgefahren, wenn es noch scharf ist — ein Hintergrund-Refresh bei
   * offenem Dialog darf nicht dazu fuehren, dass der Knopf etwas schaltet,
   * das laengst jemand anders geschaltet hat (Regel aus confirmToggle).
   */
  confirmDisarm(): void {
    const request = this.pendingDisarm;
    this.pendingDisarm = null;
    if (!request) {
      return;
    }
    if (request.kind === 'camera') {
      const current = this.cameras.find(c => c.cameraId === request.id);
      if (current?.armed) {
        this.armCamera(request.id, false);
      }
    } else {
      const group = this.groups.find(g => g.syncName === request.id);
      if (group?.syncArmed) {
        this.armSystem(request.id, false);
      }
    }
  }

  private armCamera(cameraId: string, armed: boolean): void {
    this.armBusy.add(cameraId);
    this.blinkService.setCameraArmed(cameraId, armed).subscribe({
      next: () => { this.armBusy.delete(cameraId); this.load(true); },
      error: () => { this.armBusy.delete(cameraId); }
    });
  }

  private armSystem(syncName: string, armed: boolean): void {
    const key = `sync:${syncName}`;
    this.armBusy.add(key);
    this.blinkService.setSystemArmed(syncName, armed).subscribe({
      next: () => { this.armBusy.delete(key); this.load(true); },
      error: () => { this.armBusy.delete(key); }
    });
  }
```

`load(...)` setzt zusätzlich `this.groups = this.groupBySync(cameras);` (Methode 1:1 aus `CamerasComponent` übernehmen — die bewusste Doppelung zwischen Website- und Tablet-Variante ist Projektlinie). Außerdem `onThumbnailError`/`hasThumbnailError`/`playLastMotion` wie in Task 6.

`tablet-cameras.component.html` — Grid in Gruppen fassen (Container-Klasse `.camera-groups`), je Gruppe Kopfzeile mit `system-arm-toggle`, je Kachel `camera-arm-toggle` (Beschriftung Scharf/Unscharf), Bewegungszeile `last-motion`, Platzhalter wie Task 6; dazu der Dialog (eigenes Markup, Muster confirm_required — statischer Warntext + rote Bestätigung):

```html
  @if (pendingDisarm) {
    <div class="confirm-overlay" (click)="cancelDisarm()">
      <div class="confirm-dialog" (click)="$event.stopPropagation()">
        <h3>Wirklich unscharf schalten?</h3>
        <p>
          {{ pendingDisarm.kind === 'system'
              ? 'Das gesamte System „' + pendingDisarm.name + '" zeichnet dann keine Bewegungen mehr auf.'
              : 'Die Kamera „' + pendingDisarm.name + '" zeichnet dann keine Bewegungen mehr auf.' }}
        </p>
        <div class="confirm-actions">
          <button type="button" class="dialog-cancel" (click)="cancelDisarm()">Abbrechen</button>
          <button type="button" class="dialog-confirm" (click)="confirmDisarm()">Unscharf schalten</button>
        </div>
      </div>
    </div>
  }
```

SCSS: `.system-arm-toggle`/`.camera-arm-toggle` im Stil der vorhandenen Tile-Buttons (scharf = grünlich wie `.status-badge.armed`); `.confirm-overlay` als Vollbild-Overlay (Muster `.clip-player`), `.dialog-confirm` rot (`background: #dc2626; color: #fff`); `.thumbnail-placeholder`/`.last-motion` wie Task 6, farblich ans dunkle Theme angepasst (`color: #93c5fd` für `.last-motion`). Der bisherige separate `status-badge` kann durch den Schalter ersetzt werden (der Schalter zeigt den Zustand).

- [ ] **Step 4: Tests laufen lassen (inkl. Schwester-Specs)**

```bash
npx ng test --watch=false --browsers=ChromeHeadless --include="**/tablet-cameras.component.spec.ts"
npx ng test --watch=false --browsers=ChromeHeadless --include="**/tablet-air-quality.component.spec.ts"
npx ng test --watch=false --browsers=ChromeHeadless --include="**/tablet-temperatures.component.spec.ts"
```

Expected: alle PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/tablet-cameras
git commit -m "feat(blink): Tablet schaltet Kameras (Unscharf mit Bestaetigungsdialog)"
```

---

### Task 8: Doku + Gesamtverifikation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Blink-Kamera-Dashboard")
- Modify: Memory (`blink-kamera-dashboard.md`)

- [ ] **Step 1: Gesamtläufe**

```powershell
Set-Location backend; mvn test -Dtest="Blink*,SecurityRulesTest"     # erwartet: gruen
Set-Location ..\blink-vision; .venv\Scripts\python -m pytest tests/ -q  # erwartet: gruen
Set-Location ..\frontend; npx ng test --watch=false --browsers=ChromeHeadless  # erwartet: nur die 3 Baseline-Fails
```

- [ ] **Step 2: CLAUDE.md-Abschnitt „Blink-Kamera-Dashboard" aktualisieren**

Drei bestehende Aussagen sind durch die Revision FALSCH geworden und werden ersetzt (nicht ergänzt):

1. Die Zeile zu den Rollen („Scharf/Unscharf fällt auf `anyRequest` → **MEMBER**…") → neu: KIOSK darf beide Richtungen (Revision 2026-08-27, Nutzerentscheidung); Schutz gegen Versehen ist der Bestätigungsdialog der Tablet-Ansicht (UI-Schutz, Muster `confirm_required`), einen serverseitigen Schutz gegen Fremde vor dem Wandtablet gibt es bewusst nicht mehr.
2. Die Zeile zur Tablet-Ansicht („hat die Scharf-Steuerung gar nicht erst im Markup…") → neu: Tablet hat Schalter und Dialog; die Whitelist-Test-Idee bleibt (jedes unbekannte Bedienelement lässt den Test scheitern), die Typ-Sperre umfasst jetzt auch die Schaltmethoden.
3. Ergänzen: Bewegungsmeldung (MotionWatcher, 30-s-Takt, Hochwassermarke, Webhook-erst-dann-Marke, `event.blink_<cameraId>_motion`, `lastMotionAt` nur im Speicher, Latenz 15–60 s, Neustart-Lücken beidseitig), Thumbnail-Platzhalter, und der Prüfpunkt „`createdAt`-Zeitzone beim Realtest kontrollieren".

- [ ] **Step 3: Memory aktualisieren**

`C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\blink-kamera-dashboard.md`: die Revision (KIOSK schaltet, Dialog statt Serversperre), die Bewegungsmeldung samt offener Zeitzonenfrage und den weiterhin ausstehenden Realtest nachtragen. Indexzeile in `MEMORY.md` anpassen.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(blink): Bewegungsmeldung und Tablet-Schalten dokumentiert"
```

---

## Verifikation nach Abschluss (manuell, beim Rollout)

1. Deploy; Bewegung vor einer Kamera auslösen → nach ≤ 60 s: Ereignis in den Flow-Debug-Einträgen sichtbar, „Letzte Bewegung" an der Kachel, Klick spielt den Clip.
2. Zeitstempel der Bewegung mit der echten Uhrzeit vergleichen (UTC-vs.-Lokal-Frage aus der Spec).
3. Tablet: Scharf direkt, Unscharf fragt nach; Dialog offen lassen, an anderer Stelle unscharf schalten → Bestätigen schaltet nichts.
4. Kamera ohne je hochgeladenes Standbild → Platzhalter statt leerem Bild; Schnappschuss ersetzt ihn.
5. Danach den Benachrichtigungs-Flow via flow-mcp anlegen (z. B. „`event.blink_<id>_motion` + Modus Abwesend an → Push"); kein Trigger auf `value: "unavailable"` (tote-Trigger-Falle).
