"""HTTP-Schicht des Kamera-Dashboards: Routen und - vor allem - die Abbildung
der Client-Ausnahmen auf Statuscodes.

Diese Abbildung ist der eigentliche Wert der Schicht und Teil des Vertrags mit
dem Spring-Backend: 409 = nicht angemeldet (das Backend macht daraus 400 mit
Login-Hinweis; ein 401 wuerde den Nutzer aus seiner Haushalts-Sitzung werfen),
404 = unbekannte Kamera/Clip, 502 = sonstiger Blink-Fehler. Getestet gegen einen
gefakten BlinkClient - kein Zugriff auf die Blink-Cloud.
"""
import pytest

pytest.importorskip("fastapi.testclient")

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.blink_client import BlinkNotLoggedInError  # noqa: E402
from app.cameras import build_router  # noqa: E402


class _FakeBlink:
    """Blink-Client-Attrappe: liefert vorgegebene Werte oder wirft eine
    vorgegebene Ausnahme, und merkt sich, womit sie aufgerufen wurde."""

    def __init__(self, error: Exception | None = None, cameras=None,
                 image=b"jpeg-bytes", clips=None, clip_path=""):
        self._error = error
        self._cameras = cameras if cameras is not None else []
        self._image = image
        self._clips = clips if clips is not None else []
        self._clip_path = clip_path
        self.force_arg = None
        self.calls: list[tuple] = []

    def _answer(self, value):
        if self._error is not None:
            raise self._error
        return value

    async def list_cameras(self, force: bool = False):
        self.force_arg = force
        return self._answer(self._cameras)

    async def set_camera_armed(self, camera_id: str, armed: bool):
        self.calls.append(("camera", camera_id, armed))
        return self._answer(None)

    async def set_sync_armed(self, sync_name: str, armed: bool):
        self.calls.append(("sync", sync_name, armed))
        return self._answer(None)

    async def snapshot(self, camera_id: str):
        self.calls.append(("snapshot", camera_id))
        return self._answer(self._image)

    async def thumbnail(self, camera_id: str):
        self.calls.append(("thumbnail", camera_id))
        return self._answer(self._image)

    async def list_clips(self, camera_id: str):
        self.calls.append(("clips", camera_id))
        return self._answer(self._clips)

    async def fetch_clip(self, camera_id: str, clip_id: str, cache_dir: str):
        self.calls.append(("clip", camera_id, clip_id, cache_dir))
        return self._answer(self._clip_path)


def _client(blink: _FakeBlink) -> TestClient:
    app = FastAPI()
    app.include_router(build_router(blink))
    return TestClient(app)


# Alle Endpunkte, damit die Statuscode-Abbildung nicht nur an einem haengt.
ALL_ENDPOINTS = [
    ("get", "/cameras"),
    ("post", "/cameras/1/arm"),
    ("post", "/cameras/1/disarm"),
    ("post", "/system/Zuhause/arm"),
    ("post", "/system/Zuhause/disarm"),
    ("post", "/cameras/1/snapshot"),
    ("get", "/cameras/1/thumbnail"),
    ("get", "/cameras/1/clips"),
    ("get", "/cameras/1/clips/10"),
]


# ==================== Statuscode-Abbildung ====================


@pytest.mark.parametrize("method,path", ALL_ENDPOINTS)
def test_not_logged_in_becomes_409(method, path):
    blink = _FakeBlink(error=BlinkNotLoggedInError("Nicht bei Blink angemeldet."))

    response = getattr(_client(blink), method)(path)

    assert response.status_code == 409
    assert "angemeldet" in response.json()["detail"]["error"]


@pytest.mark.parametrize("method,path", ALL_ENDPOINTS)
def test_unknown_camera_or_clip_becomes_404(method, path):
    blink = _FakeBlink(error=KeyError("Kamera 1 nicht gefunden"))

    response = getattr(_client(blink), method)(path)

    assert response.status_code == 404
    assert "nicht gefunden" in response.json()["detail"]["error"]


@pytest.mark.parametrize("method,path", ALL_ENDPOINTS)
def test_any_other_error_becomes_502(method, path):
    blink = _FakeBlink(error=RuntimeError("Cloud kaputt"))

    response = getattr(_client(blink), method)(path)

    assert response.status_code == 502
    assert "Cloud kaputt" in response.json()["detail"]["error"]


def test_snapshot_timeout_becomes_502():
    """snapshot() wirft TimeoutError, wenn Blink im Zeitbudget kein neues Bild
    liefert. TimeoutError erbt von OSError - also weder vom Nicht-angemeldet-
    noch vom KeyError-Zweig gefangen und korrekt ein 502."""
    blink = _FakeBlink(error=TimeoutError("Blink hat kein neues Standbild geliefert"))

    response = _client(blink).post("/cameras/1/snapshot")

    assert response.status_code == 502
    assert "Standbild" in response.json()["detail"]["error"]


# ==================== force-Durchreichung ====================


def test_list_cameras_defaults_to_the_throttled_refresh():
    blink = _FakeBlink()

    _client(blink).get("/cameras")

    assert blink.force_arg is False


def test_list_cameras_passes_force_through():
    """Ohne force=true zeigte das Dashboard nach einem Schaltbefehl weiter den
    alten Zustand - blinkpys refresh() ist 30 s gedrosselt."""
    blink = _FakeBlink()

    _client(blink).get("/cameras", params={"force": "true"})

    assert blink.force_arg is True


# ==================== Nutzdaten ====================


def test_list_cameras_returns_the_client_payload():
    blink = _FakeBlink(cameras=[{"cameraId": "1", "name": "Haustuer"}])

    response = _client(blink).get("/cameras")

    assert response.status_code == 200
    assert response.json() == [{"cameraId": "1", "name": "Haustuer"}]


def test_snapshot_returns_jpeg_bytes():
    blink = _FakeBlink(image=b"\xff\xd8-bild")

    response = _client(blink).post("/cameras/1/snapshot")

    assert response.status_code == 200
    assert response.headers["content-type"] == "image/jpeg"
    assert response.content == b"\xff\xd8-bild"


def test_thumbnail_returns_jpeg_bytes():
    blink = _FakeBlink(image=b"\xff\xd8-thumb")

    response = _client(blink).get("/cameras/1/thumbnail")

    assert response.headers["content-type"] == "image/jpeg"
    assert response.content == b"\xff\xd8-thumb"


@pytest.mark.parametrize("path,armed", [("arm", True), ("disarm", False)])
def test_camera_arm_endpoints_switch_and_report(path, armed):
    blink = _FakeBlink()

    response = _client(blink).post(f"/cameras/7/{path}")

    assert response.json() == {"armed": armed}
    assert blink.calls == [("camera", "7", armed)]


@pytest.mark.parametrize("path,armed", [("arm", True), ("disarm", False)])
def test_system_arm_endpoints_switch_and_report(path, armed):
    blink = _FakeBlink()

    response = _client(blink).post(f"/system/Zuhause/{path}")

    assert response.json() == {"armed": armed}
    assert blink.calls == [("sync", "Zuhause", armed)]


def test_list_clips_returns_the_client_payload():
    blink = _FakeBlink(clips=[{"clipId": "10"}])

    assert _client(blink).get("/cameras/1/clips").json() == [{"clipId": "10"}]


def test_clip_is_served_as_mp4_from_the_cache_path(tmp_path):
    video = tmp_path / "clip-10.mp4"
    video.write_bytes(b"video-bytes")
    blink = _FakeBlink(clip_path=str(video))

    response = _client(blink).get("/cameras/1/clips/10")

    assert response.status_code == 200
    assert response.headers["content-type"] == "video/mp4"
    assert response.content == b"video-bytes"


def test_clip_endpoint_passes_the_cache_directory(tmp_path):
    from app import cameras

    video = tmp_path / "clip-10.mp4"
    video.write_bytes(b"video-bytes")
    blink = _FakeBlink(clip_path=str(video))

    _client(blink).get("/cameras/1/clips/10")

    assert blink.calls == [("clip", "1", "10", cameras.CLIP_CACHE_DIR)]


# ==================== Smoke-Test: Routen in der echten App ====================


def test_camera_routes_registered():
    """Gefragt wird das OpenAPI-Schema, nicht app.routes: eingehaengte Router
    erscheinen dort in neueren FastAPI-Versionen als Wrapper-Objekt ohne
    eigenes .path - der naheliegende Zugriff ginge also am Ziel vorbei."""
    from app.main import app

    paths = set(app.openapi()["paths"])
    assert "/cameras" in paths
    assert "/cameras/{camera_id}/clips/{clip_id}" in paths
    assert "/system/{sync_name}/arm" in paths
