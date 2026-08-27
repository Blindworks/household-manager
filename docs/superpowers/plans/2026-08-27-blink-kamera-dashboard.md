# Blink-Kamera-Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dashboard (Website + Wandtablet) für alle Blink-Kameras mit Scharf/Unscharf, Schnappschuss und Clip-Wiedergabe; Scharf-Zustände als Entitäten (`EntitySource.BLINK`).

**Architecture:** Der blink-vision-Sidecar (hält die einzige Blink-Session) bekommt neue Kamera-Endpunkte; das Backend-Modul `blink/` proxied sie (`/api/v1/blink`), streamt Medien durch und pollt die Zustände in den Entity-State-Layer. Frontend: Seite `/cameras` + Tablet-Ansicht `/tablet/cameras`. Spec: `docs/superpowers/specs/2026-08-27-blink-kamera-dashboard-design.md`.

**Tech Stack:** Python/FastAPI/blinkpy 0.25.9 (Sidecar), Spring Boot 3.4/Java 21 (Backend), Angular 19 (Frontend).

**Wichtige Bestandsregeln:**
- Alle blinkpy-Spezifika leben AUSSCHLIESSLICH in `blink-vision/app/blink_client.py`.
- Java-HTTP-Client gegen den Python-Sidecar MUSS HTTP/1.1 erzwingen (h2c-Upgrade verliert den Body gegen uvicorn).
- Backend-Build: JAVA_HOME auf JDK 21 setzen (`$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'` o. ä. laut Memory `backend-jdk21-build.md`); lokale DB-Tests (`contextLoads`) sind eine bekannte rote Baseline.
- Frontend-Test-Baseline: 3 vorbestehende Fails (App/Hero) + SmartDeviceList-Flake sind bekannt.
- Der Dashboard-Clip-Pfad darf den Dedupe-Store des Erkennungs-Pollers NICHT anfassen.
- Niemals 401 aus Blink-Fehlern (Auth-Interceptor wirft sonst aus der Haushalts-Session); „nicht angemeldet" = 400 via `IllegalStateException`, Sidecar-/Cloud-Fehler = 502 via `BlinkException`.

---

### Task 1: blinkpy-Bezeichner für Arm/Snapshot/Thumbnail verifizieren (Spike)

Die bestehende Verifikationstabelle `blink-vision/BLINKPY-API.md` deckt Login/Clips ab, aber NICHT Arm/Snapshot/Thumbnail. Vor jedem Code statisch gegen die installierte Quelle prüfen (kein Netzzugriff).

**Files:**
- Modify: `blink-vision/BLINKPY-API.md` (Tabelle ergänzen)

- [ ] **Step 1: Bezeichner in der installierten Bibliothek prüfen**

```bash
cd blink-vision
grep -n "def arm\|def async_arm\|def battery\|def snap_picture\|def image_from_cache\|self.sync\b" .venv/Lib/site-packages/blinkpy/camera.py
grep -n "def arm\|def async_arm\|self.cameras\|def name" .venv/Lib/site-packages/blinkpy/sync_module.py
```

Erwartet (jeweils bestätigen, sonst die Folge-Tasks an die realen Namen anpassen):
- `BlinkCamera.arm` — Property (bool, Bewegungserkennung an)
- `BlinkCamera.async_arm(value)` — async, schaltet die Kamera scharf/unscharf
- `BlinkCamera.battery` — Property (String, z. B. `"ok"`)
- `BlinkCamera.snap_picture()` — async, löst ein neues Standbild aus
- `BlinkCamera.image_from_cache` — Property (JPEG-Bytes oder None)
- `BlinkSyncModule.arm` — Property (bool), `BlinkSyncModule.async_arm(value)` — async
- `BlinkSyncModule.cameras` — dict Name → BlinkCamera (deckt auch Owls/Minis ab, `BlinkOwl` erbt von `BlinkSyncModule`)
- `Blink.refresh(force=False)` — async; ohne `force` intern über `refresh_rate` (30 s) gedrosselt

- [ ] **Step 2: BLINKPY-API.md um die neuen Zeilen ergänzen**

An die bestehende Ergebnis-Tabelle anhängen (Fundstellen mit den realen Zeilennummern aus Step 1):

```markdown
## Ergänzung Kamera-Dashboard (2026-08-27)

| Erwarteter Bezeichner | Existiert | Tatsächlicher Name / Signatur | Fundstelle |
| --- | --- | --- | --- |
| `cam.arm` | ja/nein | ... | `camera.py:...` |
| `cam.async_arm(value)` | ja/nein | ... | `camera.py:...` |
| `cam.battery` | ja/nein | ... | `camera.py:...` |
| `cam.snap_picture()` | ja/nein | ... | `camera.py:...` |
| `cam.image_from_cache` | ja/nein | ... | `camera.py:...` |
| `sync.arm` / `sync.async_arm(value)` | ja/nein | ... | `sync_module.py:...` |
| `sync.cameras` | ja/nein | ... | `sync_module.py:...` |
| `blink.refresh(force=...)` | ja/nein | ... | `blinkpy.py:...` |
```

- [ ] **Step 3: Commit**

```bash
git add blink-vision/BLINKPY-API.md
git commit -m "docs(blink): blinkpy-Bezeichner fuer Kamera-Steuerung verifiziert"
```

---

### Task 2: Sidecar — Kamera-Funktionen in `blink_client.py`

**Files:**
- Modify: `blink-vision/app/blink_client.py`
- Test: `blink-vision/tests/test_camera_dashboard.py`

- [ ] **Step 1: Failing Tests schreiben**

`blink-vision/tests/test_camera_dashboard.py` (Fakes per SimpleNamespace, Muster `test_camera_selection.py`):

```python
"""Pure Mapping-/Lookup-Logik des Kamera-Dashboards (ohne echte Blink-Cloud)."""
from datetime import datetime
from types import SimpleNamespace

from app.blink_client import _camera_summary, _clip_summary, _find_in_syncs


def _cam(camera_id="123", camera_type="doorbell", arm=True, battery="ok"):
    return SimpleNamespace(camera_id=camera_id, camera_type=camera_type,
                           arm=arm, battery=battery)


def test_camera_summary_maps_all_fields():
    summary = _camera_summary("Haustuer", _cam(), "Zuhause", True)
    assert summary == {
        "cameraId": "123",
        "name": "Haustuer",
        "type": "doorbell",
        "armed": True,
        "battery": "ok",
        "syncName": "Zuhause",
        "syncArmed": True,
    }


def test_camera_summary_tolerates_missing_battery_and_type():
    summary = _camera_summary("Innen", _cam(camera_type="", battery=None), "Zuhause", False)
    assert summary["battery"] is None
    assert summary["type"] == ""
    assert summary["armed"] is True


def test_clip_summary_uses_iso_timestamp():
    item = SimpleNamespace(id=42, name="Haustuer",
                           created_at=datetime(2026, 8, 27, 14, 30, 5), size=1234)
    assert _clip_summary(item) == {
        "clipId": "42",
        "createdAt": "2026-08-27T14:30:05",
        "sizeBytes": 1234,
    }


def test_find_in_syncs_matches_by_camera_id_not_name():
    cam_a, cam_b = _cam(camera_id="1"), _cam(camera_id="2")
    syncs = {"Zuhause": SimpleNamespace(cameras={"A": cam_a, "B": cam_b}, arm=True)}
    name, cam, sync = _find_in_syncs(syncs, "2")
    assert name == "B" and cam is cam_b and sync is syncs["Zuhause"]


def test_find_in_syncs_returns_none_for_unknown_id():
    syncs = {"Zuhause": SimpleNamespace(cameras={"A": _cam(camera_id="1")}, arm=True)}
    assert _find_in_syncs(syncs, "999") is None
```

- [ ] **Step 2: Tests laufen lassen — sie müssen fehlschlagen**

```bash
cd blink-vision
.venv\Scripts\python -m pytest tests/test_camera_dashboard.py -v
```

Expected: FAIL / ImportError (`_camera_summary` existiert nicht).

- [ ] **Step 3: Implementierung in `blink_client.py`**

Ans Modulende (vor der Klasse die Modul-Helfer, die Methoden in die Klasse `BlinkClient`):

```python
class BlinkNotLoggedInError(RuntimeError):
    """Aktion verlangt eine aktive Blink-Anmeldung."""


def _camera_summary(name: str, cam, sync_name: str, sync_armed: bool) -> dict:
    """Reines Mapping BlinkCamera -> API-Dict (testbar ohne Cloud)."""
    battery = getattr(cam, "battery", None)
    return {
        "cameraId": str(cam.camera_id),
        "name": name,
        "type": str(getattr(cam, "camera_type", "") or ""),
        "armed": bool(cam.arm),
        "battery": str(battery) if battery is not None else None,
        "syncName": sync_name,
        "syncArmed": bool(sync_armed),
    }


def _clip_summary(item) -> dict:
    return {
        "clipId": str(item.id),
        "createdAt": item.created_at.isoformat(),
        "sizeBytes": getattr(item, "size", None),
    }


def _find_in_syncs(syncs, camera_id: str):
    """Sucht eine Kamera ueber die stabile camera_id (Namen sind umbenennbar).
    Liefert (name, camera, sync) oder None."""
    for sync in syncs.values():
        for name, cam in sync.cameras.items():
            if str(cam.camera_id) == camera_id:
                return name, cam, sync
    return None
```

Methoden in `BlinkClient` (nach `fetch_new_clips`):

```python
    # ==================== Kamera-Dashboard ====================

    def _require_login(self):
        if not self.logged_in:
            raise BlinkNotLoggedInError("Nicht bei Blink angemeldet.")
        return self._blink

    async def list_cameras(self) -> list[dict]:
        """Alle Kameras aller Sync-Module (auch Minis/Owls - BlinkOwl erbt von
        BlinkSyncModule und taucht in blink.sync auf). refresh() ist intern
        ueber refresh_rate gedrosselt, wiederholte Aufrufe kosten die Cloud nichts."""
        blink = self._require_login()
        await blink.refresh()
        result: list[dict] = []
        for sync_name, sync in blink.sync.items():
            for cam_name, cam in sync.cameras.items():
                result.append(_camera_summary(cam_name, cam, sync_name, bool(sync.arm)))
        return result

    async def set_camera_armed(self, camera_id: str, armed: bool) -> None:
        blink = self._require_login()
        found = _find_in_syncs(blink.sync, camera_id)
        if found is None:
            raise KeyError(f"Kamera {camera_id} nicht gefunden")
        _, cam, _ = found
        await cam.async_arm(armed)

    async def set_sync_armed(self, sync_name: str, armed: bool) -> None:
        blink = self._require_login()
        if sync_name not in blink.sync:  # CaseInsensitiveDict
            raise KeyError(f"Sync-Modul {sync_name} nicht gefunden")
        await blink.sync[sync_name].async_arm(armed)

    async def snapshot(self, camera_id: str) -> bytes:
        """Loest ein neues Standbild aus und liefert es zurueck. Der force-Refresh
        umgeht die refresh_rate-Drossel, sonst kaeme noch das alte Bild."""
        blink = self._require_login()
        found = _find_in_syncs(blink.sync, camera_id)
        if found is None:
            raise KeyError(f"Kamera {camera_id} nicht gefunden")
        _, cam, _ = found
        await cam.snap_picture()
        await blink.refresh(force=True)
        image = cam.image_from_cache
        if not image:
            raise RuntimeError("Blink hat kein neues Standbild geliefert")
        return image

    async def thumbnail(self, camera_id: str) -> bytes:
        blink = self._require_login()
        found = _find_in_syncs(blink.sync, camera_id)
        if found is None:
            raise KeyError(f"Kamera {camera_id} nicht gefunden")
        _, cam, _ = found
        image = cam.image_from_cache
        if not image:
            await blink.refresh(force=True)
            image = cam.image_from_cache
        if not image:
            raise RuntimeError("Kein Standbild verfuegbar")
        return image

    async def list_clips(self, camera_id: str) -> list[dict]:
        """Clips der Kamera aus dem Local-Storage-Manifest, neueste zuerst.
        WICHTIG: liest nur - der Dedupe-Store des Erkennungs-Pollers bleibt unberuehrt."""
        blink = self._require_login()
        found = _find_in_syncs(blink.sync, camera_id)
        if found is None:
            raise KeyError(f"Kamera {camera_id} nicht gefunden")
        cam_name, _, _ = found
        clips: list[dict] = []
        for sync in blink.sync.values():
            if not sync.local_storage:
                continue
            await sync.refresh()
            manifest = sync._local_storage.get("manifest") or []
            # SortedSet aufsteigend nach created_at -> rueckwaerts = neueste zuerst
            for item in reversed(manifest):
                if item.name == cam_name:
                    clips.append(_clip_summary(item))
        return clips

    async def fetch_clip(self, camera_id: str, clip_id: str, cache_dir: str) -> str:
        """Laedt einen Clip in den Cache (einmal pro clip_id) und liefert den Pfad."""
        blink = self._require_login()
        found = _find_in_syncs(blink.sync, camera_id)
        if found is None:
            raise KeyError(f"Kamera {camera_id} nicht gefunden")
        cam_name, _, _ = found
        target = Path(cache_dir) / f"clip-{clip_id}.mp4"
        if target.exists():
            return str(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        for sync in blink.sync.values():
            if not sync.local_storage:
                continue
            await sync.refresh()
            manifest = sync._local_storage.get("manifest") or []
            for item in reversed(manifest):
                if str(item.id) == clip_id and item.name == cam_name:
                    await item.prepare_download(blink)
                    if not await item.download_video(blink, str(target)):
                        raise RuntimeError(f"Clip {clip_id} konnte nicht geladen werden")
                    return str(target)
        raise KeyError(f"Clip {clip_id} nicht gefunden")
```

- [ ] **Step 4: Tests laufen lassen**

```bash
.venv\Scripts\python -m pytest tests/ -v
```

Expected: alle PASS (auch die Bestandstests).

- [ ] **Step 5: Commit**

```bash
git add app/blink_client.py tests/test_camera_dashboard.py
git commit -m "feat(blink-vision): Kamera-Funktionen fuer das Dashboard im BlinkClient"
```

---

### Task 3: Sidecar — FastAPI-Endpunkte

**Files:**
- Create: `blink-vision/app/cameras.py`
- Modify: `blink-vision/app/main.py`

- [ ] **Step 1: Router `app/cameras.py` anlegen**

```python
"""Kamera-Dashboard-Endpunkte: Liste, Scharf/Unscharf, Schnappschuss, Clips.

Duenne HTTP-Schicht - alle blinkpy-Zugriffe leben in blink_client.py.
'Nicht angemeldet' ist HTTP 409 (das Backend uebersetzt das in 400 mit
Login-Hinweis); unbekannte Kamera/Clip 404; Blink-/Cloud-Fehler 502.
"""
import logging
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse, Response

from app import config
from app.blink_client import BlinkClient, BlinkNotLoggedInError

log = logging.getLogger(__name__)

CLIP_CACHE_DIR = str(Path(config.DATA_DIR) / "clip-cache")


def build_router(blink: BlinkClient) -> APIRouter:
    router = APIRouter()

    async def _call(coro):
        try:
            return await coro
        except BlinkNotLoggedInError:
            raise HTTPException(status_code=409, detail={"error": "Nicht bei Blink angemeldet."})
        except KeyError as ex:
            raise HTTPException(status_code=404, detail={"error": str(ex)})
        except HTTPException:
            raise
        except Exception as ex:
            log.warning("Blink-Kamera-Aufruf fehlgeschlagen: %s", ex)
            raise HTTPException(status_code=502, detail={"error": f"Blink-Fehler: {ex}"})

    @router.get("/cameras")
    async def list_cameras():
        return await _call(blink.list_cameras())

    @router.post("/cameras/{camera_id}/arm")
    async def arm_camera(camera_id: str):
        await _call(blink.set_camera_armed(camera_id, True))
        return {"armed": True}

    @router.post("/cameras/{camera_id}/disarm")
    async def disarm_camera(camera_id: str):
        await _call(blink.set_camera_armed(camera_id, False))
        return {"armed": False}

    @router.post("/system/{sync_name}/arm")
    async def arm_system(sync_name: str):
        await _call(blink.set_sync_armed(sync_name, True))
        return {"armed": True}

    @router.post("/system/{sync_name}/disarm")
    async def disarm_system(sync_name: str):
        await _call(blink.set_sync_armed(sync_name, False))
        return {"armed": False}

    @router.post("/cameras/{camera_id}/snapshot")
    async def snapshot(camera_id: str):
        image = await _call(blink.snapshot(camera_id))
        return Response(content=image, media_type="image/jpeg")

    @router.get("/cameras/{camera_id}/thumbnail")
    async def thumbnail(camera_id: str):
        image = await _call(blink.thumbnail(camera_id))
        return Response(content=image, media_type="image/jpeg")

    @router.get("/cameras/{camera_id}/clips")
    async def clips(camera_id: str):
        return await _call(blink.list_clips(camera_id))

    @router.get("/cameras/{camera_id}/clips/{clip_id}")
    async def clip(camera_id: str, clip_id: str):
        path = await _call(blink.fetch_clip(camera_id, clip_id, CLIP_CACHE_DIR))
        return FileResponse(path, media_type="video/mp4")

    return router
```

- [ ] **Step 2: Router in `main.py` einhängen**

In `app/main.py` nach `app = FastAPI(...)`:

```python
from app.cameras import build_router

app.include_router(build_router(blink))
```

- [ ] **Step 3: Smoke-Test (Import + Routen registriert)**

`blink-vision/tests/test_camera_dashboard.py` ergänzen:

```python
def test_camera_routes_registered():
    from app.main import app
    paths = {route.path for route in app.routes}
    assert "/cameras" in paths
    assert "/cameras/{camera_id}/clips/{clip_id}" in paths
    assert "/system/{sync_name}/arm" in paths
```

```bash
.venv\Scripts\python -m pytest tests/ -v
```

Expected: PASS. (Achtung: der Import von `app.main` zieht FastAPI-Module — schlägt er wegen fehlender Abhängigkeiten in der Test-Venv fehl, den Test mit `pytest.importorskip("fastapi")` absichern.)

- [ ] **Step 4: Commit**

```bash
git add app/cameras.py app/main.py tests/test_camera_dashboard.py
git commit -m "feat(blink-vision): Kamera-Dashboard-Endpunkte (Liste, Arm, Snapshot, Clips)"
```

---

### Task 4: Backend — `BlinkException` + `BlinkSidecarClient`

Ab hier gilt: `$env:JAVA_HOME` auf JDK 21 setzen, Tests via `mvn test -Dtest=...` in `backend/`.

**Files:**
- Create: `backend/src/main/java/com/household/manager/blink/BlinkException.java`
- Create: `backend/src/main/java/com/household/manager/blink/BlinkSidecarClient.java`
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/household/manager/blink/BlinkSidecarClientTest.java`

- [ ] **Step 1: Failing Test für das Parsing**

```java
package com.household.manager.blink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlinkSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parstKameralisteMitAllenFeldern() throws Exception {
        var json = mapper.readTree("""
                [{"cameraId":"123","name":"Haustuer","type":"doorbell","armed":true,
                  "battery":"ok","syncName":"Zuhause","syncArmed":false}]""");

        List<BlinkSidecarClient.SidecarCamera> cameras = BlinkSidecarClient.parseCameras(json);

        assertThat(cameras).containsExactly(new BlinkSidecarClient.SidecarCamera(
                "123", "Haustuer", "doorbell", true, "ok", "Zuhause", false));
    }

    @Test
    void parstKameraOhneBatterieAlsNull() throws Exception {
        var json = mapper.readTree("""
                [{"cameraId":"5","name":"Innen","type":"","armed":false,
                  "battery":null,"syncName":"Zuhause","syncArmed":true}]""");

        assertThat(BlinkSidecarClient.parseCameras(json).get(0).battery()).isNull();
    }

    @Test
    void parstClipliste() throws Exception {
        var json = mapper.readTree("""
                [{"clipId":"42","createdAt":"2026-08-27T14:30:05","sizeBytes":1234}]""");

        assertThat(BlinkSidecarClient.parseClips(json)).containsExactly(
                new BlinkSidecarClient.SidecarClip("42", "2026-08-27T14:30:05", 1234L));
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```powershell
cd backend; mvn test -Dtest=BlinkSidecarClientTest
```

Expected: Compile-FAIL (Klassen fehlen).

- [ ] **Step 3: `BlinkException` und Client implementieren**

`BlinkException.java`:

```java
package com.household.manager.blink;

/** Fehler der Blink-Kamera-Anbindung (Sidecar nicht erreichbar, Blink-Cloud-Fehler). */
public class BlinkException extends RuntimeException {

    public BlinkException(String message) {
        super(message);
    }

    public BlinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`BlinkSidecarClient.java` (Muster `VisionSidecarClient`; nutzt bewusst dieselbe
`VisionProperties.sidecarBaseUrl` — es IST derselbe Sidecar):

```java
package com.household.manager.blink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.vision.VisionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-Client fuer die Kamera-Endpunkte des blink-vision-Sidecars.
 * Derselbe Sidecar wie bei der Gesichtserkennung (VisionProperties.sidecarBaseUrl);
 * 409 vom Sidecar heisst "nicht bei Blink angemeldet" und wird als
 * IllegalStateException (-> 400) gemeldet, nie als 401.
 */
@Service
@Slf4j
public class BlinkSidecarClient {

    /** Clips koennen einige MB gross sein und kommen ueber die Blink-Cloud. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final VisionProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public BlinkSidecarClient(VisionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        // HTTP/1.1 ist Pflicht: der Default HTTP_2 versucht bei http:// ein
        // h2c-Upgrade und schickt die Anfrage ohne Body - uvicorn sieht dann
        // einen leeren Request (siehe VisionSidecarClient).
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Kamera laut Sidecar (cameraId ist die stabile Blink-Hardware-Id). */
    public record SidecarCamera(String cameraId, String name, String type, boolean armed,
                                String battery, String syncName, boolean syncArmed) {}

    /** Clip-Metadaten aus dem Local-Storage-Manifest. */
    public record SidecarClip(String clipId, String createdAt, Long sizeBytes) {}

    public List<SidecarCamera> listCameras() {
        return parseCameras(getJson("/cameras"));
    }

    public void setCameraArmed(String cameraId, boolean armed) {
        postJson("/cameras/" + encode(cameraId) + (armed ? "/arm" : "/disarm"));
    }

    public void setSyncArmed(String syncName, boolean armed) {
        postJson("/system/" + encode(syncName) + (armed ? "/arm" : "/disarm"));
    }

    public byte[] snapshot(String cameraId) {
        return sendBytes("POST", "/cameras/" + encode(cameraId) + "/snapshot");
    }

    public byte[] thumbnail(String cameraId) {
        return sendBytes("GET", "/cameras/" + encode(cameraId) + "/thumbnail");
    }

    public List<SidecarClip> listClips(String cameraId) {
        return parseClips(getJson("/cameras/" + encode(cameraId) + "/clips"));
    }

    public byte[] clip(String cameraId, String clipId) {
        return sendBytes("GET", "/cameras/" + encode(cameraId) + "/clips/" + encode(clipId));
    }

    // ==================== Parsing (testbar) ====================

    static List<SidecarCamera> parseCameras(JsonNode root) {
        List<SidecarCamera> cameras = new ArrayList<>();
        for (JsonNode node : root) {
            cameras.add(new SidecarCamera(
                    node.path("cameraId").asText(),
                    node.path("name").asText(),
                    node.path("type").asText(""),
                    node.path("armed").asBoolean(false),
                    node.path("battery").isNull() ? null : node.path("battery").asText(null),
                    node.path("syncName").asText(),
                    node.path("syncArmed").asBoolean(false)));
        }
        return cameras;
    }

    static List<SidecarClip> parseClips(JsonNode root) {
        List<SidecarClip> clips = new ArrayList<>();
        for (JsonNode node : root) {
            clips.add(new SidecarClip(
                    node.path("clipId").asText(),
                    node.path("createdAt").asText(null),
                    node.path("sizeBytes").isNumber() ? node.path("sizeBytes").asLong() : null));
        }
        return clips;
    }

    // ==================== HTTP ====================

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode getJson(String path) {
        byte[] body = sendBytes("GET", path);
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new BlinkException("Unlesbare Antwort des blink-vision-Sidecars: " + path, ex);
        }
    }

    private void postJson(String path) {
        sendBytes("POST", path);
    }

    private byte[] sendBytes(String method, String path) {
        String url = properties.getSidecarBaseUrl() + path;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response =
                    httpClient.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 409) {
                // Wird vom bestehenden IllegalStateException-Handler zu 400 -
                // NIE 401, sonst wirft der Auth-Interceptor aus der Haushalts-Session.
                throw new IllegalStateException("Nicht bei Blink angemeldet - "
                        + "Anmeldung auf der Seite Gesichtserkennung nachholen.");
            }
            if (response.statusCode() == 404) {
                throw new IllegalArgumentException("Kamera oder Clip nicht gefunden.");
            }
            if (response.statusCode() / 100 != 2) {
                throw new BlinkException("blink-vision " + path + " HTTP " + response.statusCode()
                        + ": " + extractError(response.body()));
            }
            return response.body();
        } catch (IllegalStateException | IllegalArgumentException | BlinkException ex) {
            throw ex;
        } catch (java.net.ConnectException ex) {
            throw new BlinkException(
                    "blink-vision-Sidecar ist nicht erreichbar (" + url + "). Laeuft der Dienst?", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BlinkException("Blink-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new BlinkException("Blink-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }

    private String extractError(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            JsonNode node = mapper.readTree(text);
            if (node.path("detail").has("error")) {
                return node.path("detail").path("error").asText();
            }
            return node.path("error").asText(text);
        } catch (Exception ex) {
            return text;
        }
    }
}
```

`GlobalExceptionHandler.java` — Handler ergänzen (Muster `VisionException`, nach dessen Handler einfügen; Import `com.household.manager.blink.BlinkException`):

```java
    /** Blink-Kamera-Fehler (Sidecar nicht erreichbar, Blink-Cloud-Fehler) -> 502. */
    @ExceptionHandler(BlinkException.class)
    public ResponseEntity<ErrorResponse> handleBlinkException(
            BlinkException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Blink communication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest=BlinkSidecarClientTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/blink backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java backend/src/test/java/com/household/manager/blink
git commit -m "feat(blink): Sidecar-Client und Fehlerabbildung fuer die Kamera-Anbindung"
```

---

### Task 5: Backend — `EntitySource.BLINK` + `BlinkEntityMapper`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/BlinkEntityMapper.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/BlinkEntityMapperTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlinkEntityMapperTest {

    private final BlinkEntityMapper mapper = new BlinkEntityMapper();

    private static final SidecarCamera DOOR =
            new SidecarCamera("123", "Haustuer", "doorbell", true, "ok", "Zuhause", true);
    private static final SidecarCamera INDOOR =
            new SidecarCamera("456", "Wohnzimmer", "", false, "ok", "Zuhause", true);

    @Test
    void kameraWirdZurArmedEntitaet() {
        List<EntityStateUpdate> updates = mapper.map(List.of(DOOR));

        EntityStateUpdate camera = updates.stream()
                .filter(u -> u.entityId().equals("binary_sensor.blink_123_armed"))
                .findFirst().orElseThrow();
        assertThat(camera.domain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(camera.source()).isEqualTo(EntitySource.BLINK);
        assertThat(camera.sourceRef()).isEqualTo("123");
        assertThat(camera.state()).isEqualTo("on");
        assertThat(camera.friendlyName()).isEqualTo("Haustuer scharf");
        assertThat(camera.attributes())
                .containsEntry("name", "Haustuer")
                .containsEntry("type", "doorbell")
                .containsEntry("battery", "ok")
                .containsEntry("syncName", "Zuhause");
    }

    @Test
    void jedesSyncModulErgibtGenauEineSystemEntitaet() {
        List<EntityStateUpdate> updates = mapper.map(List.of(DOOR, INDOOR));

        List<EntityStateUpdate> syncs = updates.stream()
                .filter(u -> u.entityId().startsWith("binary_sensor.blink_sync_")).toList();
        assertThat(syncs).hasSize(1);
        assertThat(syncs.get(0).entityId()).isEqualTo("binary_sensor.blink_sync_zuhause_armed");
        assertThat(syncs.get(0).state()).isEqualTo("on");
        assertThat(syncs.get(0).friendlyName()).isEqualTo("Blink Zuhause scharf");
    }

    @Test
    void unscharfeKameraMeldetOff() {
        List<EntityStateUpdate> updates = mapper.map(List.of(INDOOR));
        assertThat(updates.stream()
                .filter(u -> u.entityId().equals("binary_sensor.blink_456_armed"))
                .findFirst().orElseThrow().state()).isEqualTo("off");
    }

    @Test
    void syncNameMitSonderzeichenWirdZumSlug() {
        var cam = new SidecarCamera("9", "K", "", true, null, "Büro Süd", false);
        assertThat(mapper.map(List.of(cam)).stream()
                .map(EntityStateUpdate::entityId))
                .contains("binary_sensor.blink_sync_buero_sued_armed");
    }
}
```

- [ ] **Step 2: Test laufen lassen — Compile-FAIL erwartet**

```powershell
mvn test -Dtest=BlinkEntityMapperTest
```

- [ ] **Step 3: Implementieren**

`EntitySource.java` — vor der schließenden Klammer ergänzen:

```java
    /** Blink-Kameras (Scharf-Status via blink-vision-Sidecar). */
    BLINK
```

(Vorherigen letzten Eintrag `PRESENCE` mit Komma abschließen.)

`BlinkEntityMapper.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bildet die Kameraliste des blink-vision-Sidecars auf Entitäten ab:
 * eine {@code binary_sensor.blink_<cameraId>_armed} je Kamera und eine
 * {@code binary_sensor.blink_sync_<slug>_armed} je Sync-Modul.
 * Die cameraId ist die stabile Blink-Hardware-Id (Namen sind umbenennbar);
 * Sync-Module haben keine solche Id, ihr Name wird deshalb ge-sluggt —
 * ein umbenanntes Sync-Modul ergibt eine NEUE Entität (dokumentierter Preis).
 */
@Component
public class BlinkEntityMapper {

    public List<EntityStateUpdate> map(List<SidecarCamera> cameras) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        Map<String, SidecarCamera> syncs = new LinkedHashMap<>();
        for (SidecarCamera camera : cameras) {
            updates.add(cameraUpdate(camera));
            syncs.putIfAbsent(camera.syncName(), camera);
        }
        for (SidecarCamera representative : syncs.values()) {
            updates.add(syncUpdate(representative));
        }
        return updates;
    }

    private EntityStateUpdate cameraUpdate(SidecarCamera camera) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", camera.name());
        attributes.put("type", camera.type());
        if (camera.battery() != null) {
            attributes.put("battery", camera.battery());
        }
        attributes.put("syncName", camera.syncName());
        return EntityStateUpdate.builder()
                .entityId("binary_sensor.blink_" + camera.cameraId() + "_armed")
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.BLINK)
                .sourceRef(camera.cameraId())
                .friendlyName(camera.name() + " scharf")
                .state(camera.armed() ? "on" : "off")
                .attributes(attributes)
                .build();
    }

    private EntityStateUpdate syncUpdate(SidecarCamera camera) {
        String slug = slug(camera.syncName());
        return EntityStateUpdate.builder()
                .entityId("binary_sensor.blink_sync_" + slug + "_armed")
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.BLINK)
                .sourceRef("sync:" + slug)
                .friendlyName("Blink " + camera.syncName() + " scharf")
                .state(camera.syncArmed() ? "on" : "off")
                .attributes(Map.of("syncName", camera.syncName()))
                .build();
    }

    /** Entity-Id-tauglicher Slug (Muster CalendarCategoryKeyGenerator, verkleinert). */
    static String slug(String name) {
        String lowered = name.toLowerCase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return lowered.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest=BlinkEntityMapperTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate backend/src/test/java/com/household/manager/entitystate/mapper/BlinkEntityMapperTest.java
git commit -m "feat(blink): Scharf-Zustaende als Entitaeten (EntitySource.BLINK)"
```

---

### Task 6: Backend — `BlinkPollingService`

**Files:**
- Create: `backend/src/main/java/com/household/manager/blink/BlinkPollingService.java`
- Test: `backend/src/test/java/com/household/manager/blink/BlinkPollingServiceTest.java`

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.blink;

import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.BlinkEntityMapper;
import com.household.manager.vision.VisionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BlinkPollingServiceTest {

    private final BlinkSidecarClient client = mock(BlinkSidecarClient.class);
    private final EntityStateService entityStateService = mock(EntityStateService.class);
    private final VisionProperties properties = new VisionProperties();
    private BlinkPollingService service;

    private static final SidecarCamera DOOR =
            new SidecarCamera("123", "Haustuer", "doorbell", true, "ok", "Zuhause", true);

    @BeforeEach
    void setUp() {
        service = new BlinkPollingService(properties, client, new BlinkEntityMapper(), entityStateService);
    }

    @Test
    void meldetKameraUndSyncEntitaet() {
        when(client.listCameras()).thenReturn(List.of(DOOR));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).extracting(EntityStateUpdate::entityId)
                .containsExactlyInAnyOrder(
                        "binary_sensor.blink_123_armed",
                        "binary_sensor.blink_sync_zuhause_armed");
    }

    @Test
    void sidecarFehlerMarkiertZuletztGemeldeteUnavailableMitErhaltenenAttributen() {
        when(client.listCameras()).thenReturn(List.of(DOOR));
        service.poll();
        clearInvocations(entityStateService);

        when(client.listCameras()).thenThrow(new BlinkException("down"));
        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(update -> {
            assertThat(update.state()).isEqualTo("unavailable");
            assertThat(update.attributes()).isNotEmpty();
        });
    }

    @Test
    void nichtAngemeldetZaehltEbenfallsAlsAusfall() {
        when(client.listCameras()).thenReturn(List.of(DOOR));
        service.poll();
        clearInvocations(entityStateService);

        when(client.listCameras()).thenThrow(new IllegalStateException("nicht angemeldet"));
        service.poll();

        verify(entityStateService, times(2)).reportState(any());
    }

    @Test
    void deaktivierteIntegrationPolltNicht() {
        properties.setEnabled(false);

        service.poll();

        verifyNoInteractions(client, entityStateService);
    }

    @Test
    void pollWirftNie() {
        when(client.listCameras()).thenThrow(new RuntimeException("boom"));
        service.poll();
        // kein Throw = bestanden; ohne vorherige Updates gibt es nichts zu markieren
        verifyNoInteractions(entityStateService);
    }
}
```

- [ ] **Step 2: Test laufen lassen — Compile-FAIL erwartet**

```powershell
mvn test -Dtest=BlinkPollingServiceTest
```

- [ ] **Step 3: Implementieren** (Muster `NukiPollingService`)

```java
package com.household.manager.blink;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.BlinkEntityMapper;
import com.household.manager.vision.VisionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pollt die Kameraliste des blink-vision-Sidecars und spiegelt die
 * Scharf-Zustände in den Entity-State-Layer. Bei Fehlern (Sidecar down,
 * nicht bei Blink angemeldet) werden die zuletzt gemeldeten Entitäten
 * {@code unavailable} — mit erhaltenen Attributen, denn
 * {@code EntityStateWriter.upsert} überschreibt sie sonst mit null
 * (Muster NukiPollingService/ZigbeeAvailabilityWatchdog).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlinkPollingService {

    private final VisionProperties properties;
    private final BlinkSidecarClient client;
    private final BlinkEntityMapper mapper;
    private final EntityStateService entityStateService;

    /** Zuletzt erfolgreich gemeldete Updates; Basis für die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();

    @Scheduled(fixedDelayString = "${blink.poll-interval-ms:60000}",
            initialDelayString = "${blink.initial-delay-ms:20000}")
    public synchronized void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<EntityStateUpdate> updates = mapper.map(client.listCameras());
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
        } catch (Exception ex) {
            log.warn("Blink polling failed: {}", ex.getMessage());
            markUnavailable();
        }
    }

    private void markUnavailable() {
        for (EntityStateUpdate update : lastUpdates) {
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(update.entityId())
                    .domain(update.domain())
                    .source(update.source())
                    .sourceRef(update.sourceRef())
                    .friendlyName(update.friendlyName())
                    .state("unavailable")
                    .attributes(update.attributes())
                    .build());
        }
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest=BlinkPollingServiceTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/blink/BlinkPollingService.java backend/src/test/java/com/household/manager/blink/BlinkPollingServiceTest.java
git commit -m "feat(blink): Polling-Service spiegelt Kamera-Scharf-Zustaende"
```

---

### Task 7: Backend — `BlinkCameraService` + `BlinkController`

**Files:**
- Create: `backend/src/main/java/com/household/manager/blink/BlinkCameraService.java`
- Create: `backend/src/main/java/com/household/manager/controller/BlinkController.java`
- Test: `backend/src/test/java/com/household/manager/blink/BlinkCameraServiceTest.java`

- [ ] **Step 1: Failing Test für den Service (Audit + Nachpollen)**

```java
package com.household.manager.blink;

import com.household.manager.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.*;

class BlinkCameraServiceTest {

    private final BlinkSidecarClient client = mock(BlinkSidecarClient.class);
    private final BlinkPollingService pollingService = mock(BlinkPollingService.class);
    private final AuditService auditService = mock(AuditService.class);
    private BlinkCameraService service;

    @BeforeEach
    void setUp() {
        service = new BlinkCameraService(client, pollingService, auditService);
    }

    @Test
    void kameraScharfSchaltenAuditiertUndPolltNach() {
        service.setCameraArmed("123", true);

        InOrder order = inOrder(client, auditService, pollingService);
        order.verify(client).setCameraArmed("123", true);
        order.verify(auditService).record("blink.camera.arm", "123");
        order.verify(pollingService).poll();
    }

    @Test
    void kameraUnscharfSchaltenAuditiertDisarm() {
        service.setCameraArmed("123", false);
        verify(auditService).record("blink.camera.disarm", "123");
    }

    @Test
    void systemSchaltenAuditiertMitSyncName() {
        service.setSystemArmed("Zuhause", false);

        InOrder order = inOrder(client, auditService, pollingService);
        order.verify(client).setSyncArmed("Zuhause", false);
        order.verify(auditService).record("blink.system.disarm", "Zuhause");
        order.verify(pollingService).poll();
    }

    @Test
    void schnappschussLaeuftOhneAudit() {
        when(client.snapshot("123")).thenReturn(new byte[]{1});
        service.snapshot("123");
        verifyNoInteractions(auditService);
        verify(pollingService, never()).poll();
    }
}
```

**Hinweis:** Existiert `AuditService` in einem anderen Paket, den Import an den realen Ort anpassen (`grep -rn "class AuditService" backend/src/main/java`).

- [ ] **Step 2: Test laufen lassen — Compile-FAIL erwartet**

```powershell
mvn test -Dtest=BlinkCameraServiceTest
```

- [ ] **Step 3: Service + Controller implementieren**

`BlinkCameraService.java`:

```java
package com.household.manager.blink;

import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.blink.BlinkSidecarClient.SidecarClip;
import com.household.manager.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachschicht des Kamera-Dashboards: Proxy zum Sidecar plus Audit und
 * sofortiges Nachpollen nach Schaltaktionen (Muster NukiLockService).
 * Der Schnappschuss ist bewusst ohne Audit — er ändert nichts am Systemzustand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlinkCameraService {

    private final BlinkSidecarClient client;
    private final BlinkPollingService pollingService;
    private final AuditService auditService;

    public List<SidecarCamera> listCameras() {
        return client.listCameras();
    }

    public void setCameraArmed(String cameraId, boolean armed) {
        client.setCameraArmed(cameraId, armed);
        auditService.record(armed ? "blink.camera.arm" : "blink.camera.disarm", cameraId);
        pollingService.poll();
    }

    public void setSystemArmed(String syncName, boolean armed) {
        client.setSyncArmed(syncName, armed);
        auditService.record(armed ? "blink.system.arm" : "blink.system.disarm", syncName);
        pollingService.poll();
    }

    public byte[] snapshot(String cameraId) {
        return client.snapshot(cameraId);
    }

    public byte[] thumbnail(String cameraId) {
        return client.thumbnail(cameraId);
    }

    public List<SidecarClip> listClips(String cameraId) {
        return client.listClips(cameraId);
    }

    public byte[] clip(String cameraId, String clipId) {
        return client.clip(cameraId, clipId);
    }
}
```

`BlinkController.java` (Paket `controller`, Muster `VisionController`):

```java
package com.household.manager.controller;

import com.household.manager.blink.BlinkCameraService;
import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.blink.BlinkSidecarClient.SidecarClip;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-API des Blink-Kamera-Dashboards. Medien (Standbilder, Clips) werden vom
 * Sidecar durchgestreamt — das Frontend spricht nie direkt mit ihm.
 * Rollen: Lesen + Schnappschuss KIOSK, Scharf/Unscharf MEMBER (SecurityConfig).
 */
@RestController
@RequestMapping("/v1/blink")
@RequiredArgsConstructor
public class BlinkController {

    private final BlinkCameraService cameraService;

    @GetMapping("/cameras")
    public List<SidecarCamera> getCameras() {
        return cameraService.listCameras();
    }

    @PostMapping("/cameras/{cameraId}/arm")
    public void armCamera(@PathVariable String cameraId) {
        cameraService.setCameraArmed(cameraId, true);
    }

    @PostMapping("/cameras/{cameraId}/disarm")
    public void disarmCamera(@PathVariable String cameraId) {
        cameraService.setCameraArmed(cameraId, false);
    }

    @PostMapping("/system/{syncName}/arm")
    public void armSystem(@PathVariable String syncName) {
        cameraService.setSystemArmed(syncName, true);
    }

    @PostMapping("/system/{syncName}/disarm")
    public void disarmSystem(@PathVariable String syncName) {
        cameraService.setSystemArmed(syncName, false);
    }

    @PostMapping(value = "/cameras/{cameraId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] snapshot(@PathVariable String cameraId) {
        return cameraService.snapshot(cameraId);
    }

    @GetMapping(value = "/cameras/{cameraId}/thumbnail", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] thumbnail(@PathVariable String cameraId) {
        return cameraService.thumbnail(cameraId);
    }

    @GetMapping("/cameras/{cameraId}/clips")
    public List<SidecarClip> clips(@PathVariable String cameraId) {
        return cameraService.listClips(cameraId);
    }

    /**
     * Rueckgabetyp Resource, nicht byte[]: Spring beantwortet damit auch
     * HTTP-Range-Anfragen — Safari (iPhone-PWA) spielt <video> sonst nicht ab.
     */
    @GetMapping("/cameras/{cameraId}/clips/{clipId}")
    public ResponseEntity<Resource> clip(@PathVariable String cameraId, @PathVariable String clipId) {
        byte[] data = cameraService.clip(cameraId, clipId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new ByteArrayResource(data));
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest=BlinkCameraServiceTest
```

Expected: PASS. Danach kompletter Modul-Build als Absicherung:

```powershell
mvn test -Dtest="Blink*"
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/blink backend/src/main/java/com/household/manager/controller/BlinkController.java backend/src/test/java/com/household/manager/blink
git commit -m "feat(blink): Kamera-Service und REST-API (/v1/blink)"
```

---

### Task 8: Backend — Security-Regeln + `SecurityRulesTest`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Failing Tests ergänzen**

In `SecurityRulesTest.java` (Konvention beachten: ist der jeweilige Controller nicht im
`@WebMvcTest`-Slice, belegt 404 statt 403, dass die Regel durchlässt — Kopfkommentar
der Testklasse prüfen; `BlinkController` NICHT in den Slice aufnehmen):

```java
    /**
     * Der Schnappschuss zieht nur ein Bild, er schaltet nichts — ohne die
     * KIOSK-Whitelist-Zeile waere der Knopf auf dem Wandtablet tot
     * (Muster Speedtest/Tractive-Refresh).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfBlinkSchnappschussAusloesen() throws Exception {
        mockMvc.perform(post("/v1/blink/cameras/123/snapshot").with(csrf()))
                .andExpect(status().isNotFound());
    }

    /**
     * Scharf/Unscharf ist MEMBER (anyRequest-Regel): ein Fremder vor dem
     * Wandtablet darf die Kameras nicht unscharf schalten (Muster Nuki:
     * KIOSK darf nur verriegeln).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineBlinkKameraSchalten() throws Exception {
        mockMvc.perform(post("/v1/blink/cameras/123/disarm").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/blink/system/Zuhause/disarm").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfBlinkKamerasSchalten() throws Exception {
        mockMvc.perform(post("/v1/blink/cameras/123/arm").with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/blink/system/Zuhause/arm").with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** Lesen laeuft ueber die generische GET-Regel — bewusst keine eigene Zeile. */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfBlinkKamerasLesen() throws Exception {
        mockMvc.perform(get("/v1/blink/cameras")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/blink/cameras/123/clips")).andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Tests laufen lassen — `kioskDarfBlinkSchnappschussAusloesen` muss fehlschlagen (403 statt 404)**

```powershell
mvn test -Dtest=SecurityRulesTest
```

- [ ] **Step 3: SecurityConfig ergänzen**

Die bestehende KIOSK-POST-Whitelist-Zeile erweitern (Kommentar dort ergänzen):

```java
                        // /v1/blink/cameras/*/snapshot zieht nur ein Standbild, es
                        // schaltet nichts — sonst waere der Schnappschuss-Knopf auf
                        // dem Wandtablet tot. Scharf/Unscharf faellt bewusst auf
                        // anyRequest -> MEMBER durch.
                        .requestMatchers(HttpMethod.POST, "/v1/switches/*/toggle",
                                "/v1/modes/*/toggle", "/v1/nuki/locks/*/actions",
                                "/v1/auth/password", "/v1/tractive/pets/refresh",
                                "/v1/system/reboot", "/v1/network/speedtest",
                                "/v1/blink/cameras/*/snapshot").hasRole("KIOSK")
```

- [ ] **Step 4: Tests laufen lassen**

```powershell
mvn test -Dtest=SecurityRulesTest
```

Expected: alle PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(blink): Security-Regeln (KIOSK: lesen + Schnappschuss, MEMBER: schalten)"
```

---

### Task 9: Frontend — Model + `BlinkService`

**Files:**
- Create: `frontend/src/app/models/blink.model.ts`
- Create: `frontend/src/app/services/blink.service.ts`

- [ ] **Step 1: Model anlegen**

```typescript
/** Kamera laut GET /api/v1/blink/cameras (cameraId = stabile Blink-Hardware-Id). */
export interface BlinkCamera {
  cameraId: string;
  name: string;
  type: string;
  armed: boolean;
  battery: string | null;
  syncName: string;
  syncArmed: boolean;
}

/** Clip-Metadaten aus dem Local-Storage-Manifest der Kamera. */
export interface BlinkClip {
  clipId: string;
  createdAt: string;
  sizeBytes: number | null;
}
```

- [ ] **Step 2: Service anlegen** (Muster `NetworkService`)

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BlinkCamera, BlinkClip } from '../models/blink.model';

/** REST-Service fuer das Blink-Kamera-Dashboard (Liste, Schalten, Schnappschuss, Clips). */
@Injectable({ providedIn: 'root' })
export class BlinkService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/blink';

  getCameras(): Observable<BlinkCamera[]> {
    return this.http.get<BlinkCamera[]>(`${this.baseUrl}/cameras`);
  }

  setCameraArmed(cameraId: string, armed: boolean): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/${armed ? 'arm' : 'disarm'}`, {});
  }

  setSystemArmed(syncName: string, armed: boolean): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/system/${encodeURIComponent(syncName)}/${armed ? 'arm' : 'disarm'}`, {});
  }

  /** Loest ein neues Standbild aus; das Bild selbst laedt danach die <img> per Cache-Buster. */
  takeSnapshot(cameraId: string): Observable<Blob> {
    return this.http.post(
      `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/snapshot`, {},
      { responseType: 'blob' });
  }

  getClips(cameraId: string): Observable<BlinkClip[]> {
    return this.http.get<BlinkClip[]>(
      `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/clips`);
  }

  /** Bild-URL fuer <img>; cacheKey erzwingt nach einem Schnappschuss ein frisches Bild. */
  thumbnailUrl(cameraId: string, cacheKey: number): string {
    return `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/thumbnail?t=${cacheKey}`;
  }

  clipUrl(cameraId: string, clipId: string): string {
    return `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/clips/${encodeURIComponent(clipId)}`;
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/models/blink.model.ts frontend/src/app/services/blink.service.ts
git commit -m "feat(blink): Frontend-Model und -Service fuer das Kamera-Dashboard"
```

---

### Task 10: Frontend — Website-Seite `/cameras`

**Files:**
- Create: `frontend/src/app/pages/cameras/cameras.component.ts`
- Create: `frontend/src/app/pages/cameras/cameras.component.html`
- Create: `frontend/src/app/pages/cameras/cameras.component.scss`
- Test: `frontend/src/app/pages/cameras/cameras.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts` (Route `cameras`)
- Modify: `frontend/src/app/components/header/header.component.ts` (Navi „Smart Home")

- [ ] **Step 1: Failing Component-Tests**

`cameras.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { CamerasComponent } from './cameras.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera } from '../../models/blink.model';

const DOOR: BlinkCamera = {
  cameraId: '123', name: 'Haustuer', type: 'doorbell', armed: true,
  battery: 'ok', syncName: 'Zuhause', syncArmed: true
};

describe('CamerasComponent', () => {
  let fixture: ComponentFixture<CamerasComponent>;
  let component: CamerasComponent;
  let blinkService: jasmine.SpyObj<BlinkService>;

  beforeEach(async () => {
    blinkService = jasmine.createSpyObj('BlinkService',
      ['getCameras', 'setCameraArmed', 'setSystemArmed', 'takeSnapshot', 'getClips',
       'thumbnailUrl', 'clipUrl']);
    blinkService.getCameras.and.returnValue(of([DOOR]));
    blinkService.thumbnailUrl.and.callFake((id, key) => `/api/v1/blink/cameras/${id}/thumbnail?t=${key}`);

    await TestBed.configureTestingModule({
      imports: [CamerasComponent],
      providers: [{ provide: BlinkService, useValue: blinkService }]
    }).compileComponents();

    fixture = TestBed.createComponent(CamerasComponent);
    component = fixture.componentInstance;
  });

  it('gruppiert die Kameras nach Sync-Modul', () => {
    fixture.detectChanges();
    expect(component.groups.length).toBe(1);
    expect(component.groups[0].syncName).toBe('Zuhause');
    expect(component.groups[0].cameras).toEqual([DOOR]);
  });

  it('meldet nur beim Erstabruf einen Fehler', () => {
    blinkService.getCameras.and.returnValue(throwError(() => new Error('down')));
    fixture.detectChanges();
    expect(component.error).toBeTruthy();
  });

  it('ein fehlgeschlagener Refresh behaelt den letzten Stand', () => {
    fixture.detectChanges();
    blinkService.getCameras.and.returnValue(throwError(() => new Error('down')));
    component.reload();
    expect(component.groups.length).toBe(1);
    expect(component.error).toBeNull();
  });

  it('nicht angemeldet (400) zeigt den Login-Hinweis', () => {
    blinkService.getCameras.and.returnValue(throwError(() => ({ status: 400 })));
    fixture.detectChanges();
    expect(component.notLoggedIn).toBeTrue();
  });

  it('ein Schnappschuss erneuert den Cache-Buster erst nach der Antwort', () => {
    const snapshot$ = new Subject<Blob>();
    blinkService.takeSnapshot.and.returnValue(snapshot$.asObservable());
    fixture.detectChanges();
    const before = component.cacheKey('123');

    component.takeSnapshot(DOOR);
    expect(component.cacheKey('123')).toBe(before);
    expect(component.isSnapshotBusy('123')).toBeTrue();

    snapshot$.next(new Blob());
    snapshot$.complete();
    expect(component.cacheKey('123')).toBeGreaterThan(before);
    expect(component.isSnapshotBusy('123')).toBeFalse();
  });

  it('schaltet die Kamera und laedt danach neu', () => {
    blinkService.setCameraArmed.and.returnValue(of(void 0));
    fixture.detectChanges();
    component.toggleCamera(DOOR);
    expect(blinkService.setCameraArmed).toHaveBeenCalledWith('123', false);
    expect(blinkService.getCameras).toHaveBeenCalledTimes(2);
  });
});
```

- [ ] **Step 2: Tests laufen lassen — FAIL erwartet**

```bash
cd frontend
npx ng test --watch=false --browsers=ChromeHeadless --include="**/cameras.component.spec.ts"
```

- [ ] **Step 3: Komponente implementieren**

`cameras.component.ts`:

```typescript
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera, BlinkClip } from '../../models/blink.model';

/** Kameras eines Sync-Moduls, gruppiert fuer die Anzeige. */
export interface CameraGroup {
  syncName: string;
  syncArmed: boolean;
  cameras: BlinkCamera[];
}

/**
 * Blink-Kamera-Dashboard: Standbilder, Scharf/Unscharf (Kamera + System),
 * Schnappschuss und Clip-Wiedergabe. Selbst-Refresh 60 s; nur der Erstabruf
 * meldet einen Fehler, spaetere behalten den letzten Stand.
 */
@Component({
  selector: 'app-cameras',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './cameras.component.html',
  styleUrl: './cameras.component.scss'
})
export class CamerasComponent implements OnInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60 * 1000;

  private readonly blinkService = inject(BlinkService);
  private refreshTimer: number | null = null;

  groups: CameraGroup[] = [];
  error: string | null = null;
  /** Backend meldet 400 = keine Blink-Anmeldung; Login lebt auf /vision. */
  notLoggedIn = false;
  loaded = false;

  /** Cache-Buster je Kamera; ein Schnappschuss erneuert ihn nach Erfolg. */
  private readonly cacheKeys = new Map<string, number>();
  private readonly snapshotBusy = new Set<string>();
  private readonly armBusy = new Set<string>();
  /** Aufgeklappte Clip-Listen je Kamera. */
  readonly clips = new Map<string, BlinkClip[]>();
  readonly expandedCameras = new Set<string>();
  /** Gerade abgespielter Clip (URL fuers <video>). */
  playingClipUrl: string | null = null;

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(() => this.reload(), CamerasComponent.REFRESH_INTERVAL_MS);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  reload(): void {
    this.load(true);
  }

  cacheKey(cameraId: string): number {
    const existing = this.cacheKeys.get(cameraId);
    if (existing === undefined) {
      this.cacheKeys.set(cameraId, 1);
      return 1;
    }
    return existing;
  }

  thumbnailUrl(camera: BlinkCamera): string {
    return this.blinkService.thumbnailUrl(camera.cameraId, this.cacheKey(camera.cameraId));
  }

  isSnapshotBusy(cameraId: string): boolean {
    return this.snapshotBusy.has(cameraId);
  }

  isArmBusy(key: string): boolean {
    return this.armBusy.has(key);
  }

  toggleCamera(camera: BlinkCamera): void {
    this.armBusy.add(camera.cameraId);
    this.blinkService.setCameraArmed(camera.cameraId, !camera.armed).subscribe({
      next: () => {
        this.armBusy.delete(camera.cameraId);
        this.load(true);
      },
      error: () => {
        this.armBusy.delete(camera.cameraId);
        this.error = 'Schalten fehlgeschlagen.';
      }
    });
  }

  toggleSystem(group: CameraGroup): void {
    const key = `sync:${group.syncName}`;
    this.armBusy.add(key);
    this.blinkService.setSystemArmed(group.syncName, !group.syncArmed).subscribe({
      next: () => {
        this.armBusy.delete(key);
        this.load(true);
      },
      error: () => {
        this.armBusy.delete(key);
        this.error = 'Schalten fehlgeschlagen.';
      }
    });
  }

  takeSnapshot(camera: BlinkCamera): void {
    if (this.snapshotBusy.has(camera.cameraId)) {
      return;
    }
    this.snapshotBusy.add(camera.cameraId);
    this.blinkService.takeSnapshot(camera.cameraId).subscribe({
      next: () => {
        this.snapshotBusy.delete(camera.cameraId);
        this.cacheKeys.set(camera.cameraId, this.cacheKey(camera.cameraId) + 1);
      },
      error: () => {
        // Altes Standbild bleibt stehen (kein Cache-Buster-Update).
        this.snapshotBusy.delete(camera.cameraId);
        this.error = 'Schnappschuss fehlgeschlagen.';
      }
    });
  }

  toggleClips(camera: BlinkCamera): void {
    if (this.expandedCameras.has(camera.cameraId)) {
      this.expandedCameras.delete(camera.cameraId);
      return;
    }
    this.expandedCameras.add(camera.cameraId);
    this.blinkService.getClips(camera.cameraId).subscribe({
      next: clips => this.clips.set(camera.cameraId, clips),
      error: () => this.clips.set(camera.cameraId, [])
    });
  }

  playClip(camera: BlinkCamera, clip: BlinkClip): void {
    this.playingClipUrl = this.blinkService.clipUrl(camera.cameraId, clip.clipId);
  }

  closePlayer(): void {
    this.playingClipUrl = null;
  }

  private load(silent: boolean): void {
    this.blinkService.getCameras().subscribe({
      next: cameras => {
        this.groups = this.groupBySync(cameras);
        this.error = null;
        this.notLoggedIn = false;
        this.loaded = true;
      },
      error: err => {
        if (!silent || !this.loaded) {
          this.notLoggedIn = err?.status === 400;
          this.error = this.notLoggedIn
            ? 'Nicht bei Blink angemeldet.'
            : 'Kameras nicht verfügbar.';
        }
      }
    });
  }

  private groupBySync(cameras: BlinkCamera[]): CameraGroup[] {
    const groups = new Map<string, CameraGroup>();
    for (const camera of cameras) {
      let group = groups.get(camera.syncName);
      if (!group) {
        group = { syncName: camera.syncName, syncArmed: camera.syncArmed, cameras: [] };
        groups.set(camera.syncName, group);
      }
      group.cameras.push(camera);
    }
    return [...groups.values()];
  }
}
```

Achtung Spec-Detail „nur Erstabruf meldet": im `error`-Zweig gilt — solange noch nie
erfolgreich geladen wurde (`!this.loaded`), darf auch ein späterer Fehl-Refresh die
Meldung setzen (die Seite wäre sonst dauerhaft leer und stumm); nach dem ersten
Erfolg bleiben stille Refresh-Fehler stumm. Genau das prüfen die Tests.

`cameras.component.html`:

```html
<div class="cameras-page">
  <h1>Kameras</h1>

  @if (error) {
    <div class="error-banner">
      {{ error }}
      @if (notLoggedIn) {
        <a routerLink="/vision">Zur Blink-Anmeldung (Gesichtserkennung)</a>
      }
    </div>
  }

  @for (group of groups; track group.syncName) {
    <section class="sync-group">
      <header class="sync-header">
        <h2>{{ group.syncName }}</h2>
        <button type="button" class="arm-toggle" [class.armed]="group.syncArmed"
                [disabled]="isArmBusy('sync:' + group.syncName)"
                (click)="toggleSystem(group)">
          {{ group.syncArmed ? 'System scharf – unscharf schalten' : 'System unscharf – scharf schalten' }}
        </button>
      </header>

      <div class="camera-grid">
        @for (camera of group.cameras; track camera.cameraId) {
          <article class="camera-card">
            <img [src]="thumbnailUrl(camera)" [alt]="camera.name" loading="lazy" />
            <div class="camera-meta">
              <h3>{{ camera.name }}</h3>
              @if (camera.type === 'doorbell') {
                <span class="badge">Türklingel</span>
              }
              @if (camera.battery) {
                <span class="battery" [class.low]="camera.battery !== 'ok'">
                  Batterie: {{ camera.battery === 'ok' ? 'OK' : camera.battery }}
                </span>
              }
            </div>
            <div class="camera-actions">
              <button type="button" class="arm-toggle" [class.armed]="camera.armed"
                      [disabled]="isArmBusy(camera.cameraId)" (click)="toggleCamera(camera)">
                {{ camera.armed ? 'Scharf' : 'Unscharf' }}
              </button>
              <button type="button" class="snapshot" [disabled]="isSnapshotBusy(camera.cameraId)"
                      (click)="takeSnapshot(camera)">
                {{ isSnapshotBusy(camera.cameraId) ? 'Nimmt auf…' : 'Schnappschuss' }}
              </button>
              <button type="button" class="clips-toggle" (click)="toggleClips(camera)">
                Clips {{ expandedCameras.has(camera.cameraId) ? 'ausblenden' : 'anzeigen' }}
              </button>
            </div>
            @if (expandedCameras.has(camera.cameraId)) {
              <ul class="clip-list">
                @for (clip of clips.get(camera.cameraId) ?? []; track clip.clipId) {
                  <li>
                    <button type="button" (click)="playClip(camera, clip)">
                      {{ clip.createdAt | date:'dd.MM.yyyy HH:mm' }}
                    </button>
                  </li>
                } @empty {
                  <li class="empty">Keine Clips vorhanden.</li>
                }
              </ul>
            }
          </article>
        }
      </div>
    </section>
  } @empty {
    @if (!error) {
      <p class="empty">Keine Kameras gefunden.</p>
    }
  }

  @if (playingClipUrl) {
    <div class="clip-player" (click)="closePlayer()">
      <video [src]="playingClipUrl" controls autoplay (click)="$event.stopPropagation()"></video>
      <button type="button" (click)="closePlayer()">Schließen</button>
    </div>
  }
</div>
```

**Hinweis:** `routerLink` verlangt `RouterLink` in den Component-Imports (`import { RouterLink } from '@angular/router';` und in `imports: [CommonModule, RouterLink]`).

`cameras.component.scss` (kompakt, kein lumina — eigene Seite):

```scss
.cameras-page {
  padding: 1.5rem;
  max-width: 1100px;
  margin: 0 auto;
}

.error-banner {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #b91c1c;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;

  a {
    margin-left: 0.5rem;
    color: #b91c1c;
    text-decoration: underline;
  }
}

.sync-group {
  margin-bottom: 2rem;
}

.sync-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.75rem;
}

.camera-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.camera-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;

  img {
    width: 100%;
    aspect-ratio: 16 / 9;
    object-fit: cover;
    background: #0f172a;
    display: block;
  }
}

.camera-meta {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem 0;

  h3 {
    margin: 0;
    font-size: 1rem;
  }
}

.badge {
  font-size: 0.75rem;
  background: #eef2ff;
  color: #4338ca;
  border-radius: 999px;
  padding: 0.1rem 0.5rem;
}

.battery.low {
  color: #b45309;
}

.camera-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0.75rem;
}

.arm-toggle {
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 0.35rem 0.75rem;
  background: #f8fafc;
  cursor: pointer;

  &.armed {
    background: #dcfce7;
    border-color: #86efac;
    color: #166534;
  }
}

.snapshot,
.clips-toggle {
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 0.35rem 0.75rem;
  background: #fff;
  cursor: pointer;
}

.clip-list {
  list-style: none;
  margin: 0;
  padding: 0 0.75rem 0.75rem;

  button {
    background: none;
    border: none;
    color: #2563eb;
    cursor: pointer;
    padding: 0.25rem 0;
  }
}

.clip-player {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.85);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  z-index: 50;

  video {
    max-width: min(90vw, 960px);
    max-height: 75vh;
  }

  button {
    border: none;
    border-radius: 8px;
    padding: 0.5rem 1.25rem;
    background: #fff;
    cursor: pointer;
  }
}

.empty {
  color: #64748b;
}
```

- [ ] **Step 4: Route + Navi ergänzen**

`app.routes.ts` — nach dem `pet-food`-Eintrag:

```typescript
  {
    path: 'cameras',
    loadComponent: () => import('./pages/cameras/cameras.component').then(m => m.CamerasComponent),
    canActivate: [authGuard],
    title: 'Kameras - Household Manager'
  },
```

`header.component.ts` — im „Smart Home"-Block nach `{ path: '/pets', ... }`:

```typescript
        { path: '/cameras', label: 'Kameras' },
```

- [ ] **Step 5: Tests laufen lassen**

```bash
npx ng test --watch=false --browsers=ChromeHeadless --include="**/cameras.component.spec.ts"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/cameras frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(blink): Kamera-Dashboard-Seite /cameras"
```

---

### Task 11: Frontend — Tablet-Ansicht `/tablet/cameras`

**Files:**
- Create: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.ts`
- Create: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.html`
- Create: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.scss`
- Test: `frontend/src/app/pages/tablet-cameras/tablet-cameras.component.spec.ts`
- Modify: `frontend/src/app/shared/tablet-views.ts`
- Modify: `frontend/src/app/app.routes.ts`

- [ ] **Step 1: Failing Tests**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { TabletCamerasComponent } from './tablet-cameras.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera } from '../../models/blink.model';

const DOOR: BlinkCamera = {
  cameraId: '123', name: 'Haustuer', type: 'doorbell', armed: true,
  battery: 'ok', syncName: 'Zuhause', syncArmed: true
};

describe('TabletCamerasComponent', () => {
  let fixture: ComponentFixture<TabletCamerasComponent>;
  let blinkService: jasmine.SpyObj<BlinkService>;

  beforeEach(async () => {
    blinkService = jasmine.createSpyObj('BlinkService',
      ['getCameras', 'takeSnapshot', 'getClips', 'thumbnailUrl', 'clipUrl']);
    blinkService.getCameras.and.returnValue(of([DOOR]));
    blinkService.thumbnailUrl.and.callFake((id, key) => `/api/v1/blink/cameras/${id}/thumbnail?t=${key}`);

    await TestBed.configureTestingModule({
      imports: [TabletCamerasComponent],
      providers: [{ provide: BlinkService, useValue: blinkService }]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletCamerasComponent);
  });

  it('zeigt die Kameras mit Scharf-Badge', () => {
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Haustuer');
    expect(text).toContain('Scharf');
  });

  it('enthaelt KEINE Scharf/Unscharf-Steuerung im Markup', () => {
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    // KIOSK-Regel: die Steuerung existiert auf dem Tablet gar nicht erst,
    // nicht nur als 403 - ein Fremder soll den Weg nicht sehen.
    expect(host.querySelector('.arm-toggle')).toBeNull();
    expect(host.textContent).not.toContain('unscharf schalten');
  });

  it('hat einen Schnappschuss-Knopf', () => {
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.snapshot')).not.toBeNull();
  });

  it('nur der Erstabruf meldet einen Fehler', () => {
    blinkService.getCameras.and.returnValue(throwError(() => new Error('down')));
    fixture.detectChanges();
    expect(fixture.componentInstance.error).toBeTruthy();
  });
});
```

- [ ] **Step 2: Tests laufen lassen — FAIL erwartet**

```bash
npx ng test --watch=false --browsers=ChromeHeadless --include="**/tablet-cameras.component.spec.ts"
```

- [ ] **Step 3: Komponente implementieren**

`tablet-cameras.component.ts`:

```typescript
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera, BlinkClip } from '../../models/blink.model';

/**
 * Kamera-Ansicht fuer das Wandtablet: Standbilder, Scharf-Status und
 * Schnappschuss. BEWUSST OHNE Scharf/Unscharf-Steuerung - die KIOSK-Rolle
 * darf nicht schalten, und der Weg dorthin soll auf dem frei zugaenglichen
 * Tablet gar nicht erst sichtbar sein (Muster Nuki: nur verriegeln).
 */
@Component({
  selector: 'app-tablet-cameras',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-cameras.component.html',
  styleUrl: './tablet-cameras.component.scss'
})
export class TabletCamerasComponent implements OnInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60 * 1000;

  private readonly blinkService = inject(BlinkService);
  private refreshTimer: number | null = null;

  cameras: BlinkCamera[] = [];
  error: string | null = null;
  loaded = false;

  private readonly cacheKeys = new Map<string, number>();
  private readonly snapshotBusy = new Set<string>();
  readonly clips = new Map<string, BlinkClip[]>();
  readonly expandedCameras = new Set<string>();
  playingClipUrl: string | null = null;

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(
      () => this.load(true), TabletCamerasComponent.REFRESH_INTERVAL_MS);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  cacheKey(cameraId: string): number {
    const existing = this.cacheKeys.get(cameraId);
    if (existing === undefined) {
      this.cacheKeys.set(cameraId, 1);
      return 1;
    }
    return existing;
  }

  thumbnailUrl(camera: BlinkCamera): string {
    return this.blinkService.thumbnailUrl(camera.cameraId, this.cacheKey(camera.cameraId));
  }

  isSnapshotBusy(cameraId: string): boolean {
    return this.snapshotBusy.has(cameraId);
  }

  takeSnapshot(camera: BlinkCamera): void {
    if (this.snapshotBusy.has(camera.cameraId)) {
      return;
    }
    this.snapshotBusy.add(camera.cameraId);
    this.blinkService.takeSnapshot(camera.cameraId).subscribe({
      next: () => {
        this.snapshotBusy.delete(camera.cameraId);
        this.cacheKeys.set(camera.cameraId, this.cacheKey(camera.cameraId) + 1);
      },
      error: () => this.snapshotBusy.delete(camera.cameraId)
    });
  }

  toggleClips(camera: BlinkCamera): void {
    if (this.expandedCameras.has(camera.cameraId)) {
      this.expandedCameras.delete(camera.cameraId);
      return;
    }
    this.expandedCameras.add(camera.cameraId);
    this.blinkService.getClips(camera.cameraId).subscribe({
      next: clips => this.clips.set(camera.cameraId, clips),
      error: () => this.clips.set(camera.cameraId, [])
    });
  }

  playClip(camera: BlinkCamera, clip: BlinkClip): void {
    this.playingClipUrl = this.blinkService.clipUrl(camera.cameraId, clip.clipId);
  }

  closePlayer(): void {
    this.playingClipUrl = null;
  }

  private load(silent: boolean): void {
    this.blinkService.getCameras().subscribe({
      next: cameras => {
        this.cameras = cameras;
        this.error = null;
        this.loaded = true;
      },
      error: () => {
        if (!silent || !this.loaded) {
          this.error = 'Kameras nicht verfügbar.';
        }
      }
    });
  }
}
```

`tablet-cameras.component.html`:

```html
<app-tablet-shell heading="Kameras">
  @if (error) {
    <p class="error">{{ error }}</p>
  }

  <div class="camera-grid">
    @for (camera of cameras; track camera.cameraId) {
      <article class="camera-tile">
        <img [src]="thumbnailUrl(camera)" [alt]="camera.name" />
        <div class="tile-header">
          <h3>{{ camera.name }}</h3>
          <span class="status-badge" [class.armed]="camera.armed">
            {{ camera.armed ? 'Scharf' : 'Unscharf' }}
          </span>
        </div>
        <div class="tile-actions">
          <button type="button" class="snapshot" [disabled]="isSnapshotBusy(camera.cameraId)"
                  (click)="takeSnapshot(camera)">
            {{ isSnapshotBusy(camera.cameraId) ? 'Nimmt auf…' : 'Schnappschuss' }}
          </button>
          <button type="button" class="clips-toggle" (click)="toggleClips(camera)">
            Clips
          </button>
        </div>
        @if (expandedCameras.has(camera.cameraId)) {
          <ul class="clip-list">
            @for (clip of clips.get(camera.cameraId) ?? []; track clip.clipId) {
              <li>
                <button type="button" (click)="playClip(camera, clip)">
                  {{ clip.createdAt | date:'dd.MM. HH:mm' }}
                </button>
              </li>
            } @empty {
              <li class="empty">Keine Clips.</li>
            }
          </ul>
        }
      </article>
    } @empty {
      @if (!error) {
        <p class="empty">Keine Kameras gefunden.</p>
      }
    }
  </div>

  @if (playingClipUrl) {
    <div class="clip-player" (click)="closePlayer()">
      <video [src]="playingClipUrl" controls autoplay (click)="$event.stopPropagation()"></video>
      <button type="button" (click)="closePlayer()">Schließen</button>
    </div>
  }
</app-tablet-shell>
```

`tablet-cameras.component.scss` (dunkles Tablet-Theme wie die Schwesteransichten;
Werte ggf. an die dortigen Variablen angleichen):

```scss
:host {
  display: flex;
  flex: 1;
  min-height: 0;
}

.error {
  color: #fca5a5;
  padding: 0.5rem 1rem;
}

.camera-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0.75rem;
  padding: 0.75rem;
  overflow-y: auto;
}

.camera-tile {
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 14px;
  overflow: hidden;

  img {
    width: 100%;
    aspect-ratio: 16 / 9;
    object-fit: cover;
    display: block;
    background: #020617;
  }
}

.tile-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.5rem 0.75rem 0;

  h3 {
    margin: 0;
    color: #e2e8f0;
    font-size: 1.05rem;
  }
}

.status-badge {
  font-size: 0.8rem;
  border-radius: 999px;
  padding: 0.15rem 0.6rem;
  background: rgba(148, 163, 184, 0.25);
  color: #cbd5e1;

  &.armed {
    background: rgba(34, 197, 94, 0.25);
    color: #86efac;
  }
}

.tile-actions {
  display: flex;
  gap: 0.5rem;
  padding: 0.6rem 0.75rem 0.75rem;

  button {
    border: 1px solid rgba(148, 163, 184, 0.4);
    border-radius: 10px;
    background: rgba(30, 41, 59, 0.8);
    color: #e2e8f0;
    padding: 0.45rem 0.9rem;
  }
}

.clip-list {
  list-style: none;
  margin: 0;
  padding: 0 0.75rem 0.75rem;

  button {
    background: none;
    border: none;
    color: #93c5fd;
    padding: 0.3rem 0;
  }
}

.clip-player {
  position: fixed;
  inset: 0;
  background: rgba(2, 6, 23, 0.9);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
  z-index: 50;

  video {
    max-width: 92vw;
    max-height: 70vh;
  }

  button {
    border: none;
    border-radius: 10px;
    padding: 0.6rem 1.5rem;
    background: #e2e8f0;
  }
}

.empty {
  color: #94a3b8;
  padding: 0.5rem 1rem;
}
```

- [ ] **Step 4: Route + Ansichtsleiste**

`tablet-views.ts` — Eintrag anhängen (die Leiste scrollt seitwärts, kein Umbruch-Risiko):

```typescript
  { route: '/tablet/cameras', icon: 'videocam', label: 'Kameras' }
```

`app.routes.ts` — nach dem `tablet/network`-Eintrag:

```typescript
  {
    path: 'tablet/cameras',
    loadComponent: () => import('./pages/tablet-cameras/tablet-cameras.component').then(m => m.TabletCamerasComponent),
    canActivate: [authGuard],
    title: 'Kameras Tablet - Household Manager'
  },
```

- [ ] **Step 5: Tests laufen lassen (inkl. Schwester-Höhentests, die auf die Leiste reagieren)**

```bash
npx ng test --watch=false --browsers=ChromeHeadless
```

Expected: PASS bis auf die bekannte Baseline (3 App/Hero-Fails + SmartDeviceList-Flake). Besonders prüfen: die Höhenketten-Tests von `tablet-air-quality` und `tablet-temperatures` bleiben grün (der sechste Leisten-Eintrag darf nicht umbrechen — `.lumina__viewbar` ist `nowrap`+`overflow-x: auto`).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/tablet-cameras frontend/src/app/shared/tablet-views.ts frontend/src/app/app.routes.ts
git commit -m "feat(blink): Tablet-Ansicht /tablet/cameras (ohne Scharf-Steuerung)"
```

---

### Task 12: Doku + Gesamtverifikation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\MEMORY.md` + neue Memory-Datei

- [ ] **Step 1: Backend-Gesamtlauf**

```powershell
cd backend; mvn test
```

Expected: grün bis auf die bekannte lokale DB-Baseline (`contextLoads`).

- [ ] **Step 2: Sidecar-Testlauf**

```bash
cd blink-vision
.venv\Scripts\python -m pytest tests/ -v
```

Expected: alle PASS.

- [ ] **Step 3: CLAUDE.md-Abschnitt ergänzen**

Unter „Blink-Gesichtserkennung" einen neuen Unterabschnitt einfügen:

```markdown
### Blink-Kamera-Dashboard
- Dashboard (Seite `/cameras` + Tablet-Ansicht `/tablet/cameras`) für ALLE Blink-Kameras: Standbild, Batterie, Scharf/Unscharf (Kamera + Sync-Modul), Schnappschuss, Clip-Wiedergabe; Spec `docs/superpowers/specs/2026-08-27-blink-kamera-dashboard-design.md`
- Der blink-vision-Sidecar hält weiterhin die EINZIGE Blink-Session; die Kamera-Endpunkte (`/cameras`, `/system/...`) leben in `blink-vision/app/cameras.py`, alle blinkpy-Zugriffe in `blink_client.py`. Der Dashboard-Clip-Pfad markiert Clips NICHT als verarbeitet — der Dedupe-Store des Erkennungs-Pollers bleibt unberührt, sonst liefe ein angesehener Türclip nie durch die Gesichtserkennung
- Backend-Modul `blink/` (`BlinkSidecarClient` — HTTP/1.1 erzwungen, uvicorn-Falle; `BlinkCameraService`; `BlinkPollingService` 60 s) unter `/api/v1/blink`; Medien werden durchgestreamt, das Frontend spricht nie direkt mit dem Sidecar. Der Clip-Endpunkt gibt `Resource` zurück (nicht `byte[]`), damit Spring HTTP-Range beantwortet — Safari/iPhone-PWA spielt `<video>` sonst nicht ab
- Entitäten (`EntitySource.BLINK`): `binary_sensor.blink_<cameraId>_armed` je Kamera (stabile Hardware-Id) und `binary_sensor.blink_sync_<slug>_armed` je Sync-Modul. Der Sync-Slug kommt aus dem NAMEN — ein umbenanntes Sync-Modul ergibt eine neue Entität und lässt darauf gebaute Flows still ins Leere laufen (Muster Kalender-Kategorie-Löschung). Sidecar down oder nicht angemeldet → `unavailable` mit erhaltenen Attributen
- Rollen: Lesen + `POST .../snapshot` KIOSK (Whitelist, Muster Speedtest), Scharf/Unscharf MEMBER via `anyRequest`; die Tablet-Ansicht enthält die Scharf-Steuerung GAR NICHT im Markup (nicht nur 403). „Nicht bei Blink angemeldet" = Sidecar-409 → `IllegalStateException` → 400 mit Login-Hinweis (nie 401 — Auth-Interceptor-Falle); Login lebt weiter auf der Gesichtserkennungs-Seite
- Audit: `blink.camera.arm/disarm`, `blink.system.arm/disarm`; Schnappschuss bewusst ohne Audit
- Bewusste Grenzen v1: kein Live-View, kein Flow-Aktions-Node `blink-arm`, keine Clip-Historie in der DB (Manifest = Quelle), Clip-Latenz 15–45 s (Cloud trotz Local Storage), Clip-Cache in `DATA_DIR/clip-cache` ohne Aufräumjob
```

- [ ] **Step 4: Memory-Datei schreiben**

`C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\blink-kamera-dashboard.md`:

```markdown
---
name: blink-kamera-dashboard
description: Blink-Kamera-Dashboard (Seite + Tablet) über den blink-vision-Sidecar; Rollout und Realtest offen
metadata:
  type: project
---

Blink-Kamera-Dashboard gebaut 2026-08-27 (Spec docs/superpowers/specs/2026-08-27-blink-kamera-dashboard-design.md):
Sidecar-Endpunkte + Backend-Modul blink/ + Seiten /cameras und /tablet/cameras.
Entitäten `binary_sensor.blink_<cameraId>_armed` / `binary_sensor.blink_sync_<slug>_armed`.

**Offen:** Realtest gegen die echte Blink-Cloud (arm/snap_picture nie live ausgeführt,
nur statisch gegen blinkpy 0.25.9 verifiziert — BLINKPY-API.md); PROD-Deploy.
Sync-Modul umbenennen ändert den Entity-Slug → Flows darauf laufen still ins Leere.
Kein Flow-Aktions-Node `blink-arm` (bewusste v1-Grenze, siehe [[blink-gesichtserkennung]]).
```

In `MEMORY.md` die Indexzeile ergänzen:

```markdown
- [Blink-Kamera-Dashboard](blink-kamera-dashboard.md) — Sidecar-Erweiterung + /cameras; arm/snap nie live getestet; Sync-Umbenennung bricht Flow-Entitäten
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(blink): Kamera-Dashboard in CLAUDE.md dokumentiert"
```

---

## Verifikation nach Abschluss (manuell, mit echter Blink-Cloud)

Nicht Teil der Tasks — beim Rollout durch den Nutzer:
1. Sidecar + Backend deployen; auf `/vision` angemeldet? Dann sollte `/cameras` die Kameraliste zeigen.
2. Schnappschuss an einer Kamera auslösen (dauert Sekunden) — neues Bild erscheint.
3. Kamera unscharf/scharf schalten — Audit-Log-Eintrag prüfen, Entität in `/entities` prüfen.
4. Clip abspielen (auch auf dem iPhone/PWA — Range-Support).
5. Sync-Modul-Schalter testen; Wandtablet: Ansicht „Kameras" — kein Scharf-Schalter sichtbar, Schnappschuss funktioniert.
