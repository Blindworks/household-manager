"""Pure Mapping-/Lookup-Logik des Kamera-Dashboards (ohne echte Blink-Cloud)."""
import asyncio
from datetime import datetime
from types import SimpleNamespace

import pytest

from app import blink_client
from app.blink_client import (BlinkClient, _camera_summary, _clip_summary,
                              _find_in_syncs)


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


# ==================== Schnappschuss-Warteschleife ====================
#
# Der trickreichste Teil des Dashboards: snap_picture() weist die Kamera nur an,
# ein Bild zu machen - hochgeladen ist es erst Sekunden spaeter. Ohne die
# Warteschleife lieferte der Knopf still das ALTE Bild aus. Genau das sichern die
# folgenden Tests ab; sie schlafen dabei nicht wirklich (SNAPSHOT_POLL_SECONDS=0).


class _FakeSnapshotCamera:
    """Kamera, deren Thumbnail-URL sich nach `changes_after_refreshes` Refreshes
    aendert - so wie es die echte Cloud tut, wenn der Upload durch ist."""

    def __init__(self, changes_after_refreshes: int | None):
        self.camera_id = "123"
        self.thumbnail = "https://blink/alt.jpg"
        self.image_from_cache = b"altes-bild"
        self._changes_after = changes_after_refreshes
        self.snap_calls = 0
        self._refreshes = 0

    async def snap_picture(self):
        self.snap_calls += 1

    def on_refresh(self):
        self._refreshes += 1
        if self._changes_after is not None and self._refreshes >= self._changes_after:
            self.thumbnail = "https://blink/neu.jpg"
            self.image_from_cache = b"neues-bild"


class _FakeBlink:
    def __init__(self, cam: _FakeSnapshotCamera):
        self.sync = {"Zuhause": SimpleNamespace(cameras={"Haustuer": cam}, arm=True)}
        self._cam = cam
        self.refresh_calls: list[bool] = []

    async def refresh(self, force=False, force_cache=False):
        self.refresh_calls.append(force)
        self._cam.on_refresh()
        return True


def _client_with(blink) -> BlinkClient:
    client = BlinkClient(data_dir=".", camera_name="Haustuer")
    client._blink = blink
    return client


@pytest.fixture(autouse=True)
def _no_real_sleeping(monkeypatch):
    monkeypatch.setattr(blink_client, "SNAPSHOT_POLL_SECONDS", 0)


def test_snapshot_returns_new_image_once_thumbnail_url_changed():
    cam = _FakeSnapshotCamera(changes_after_refreshes=2)
    blink = _FakeBlink(cam)

    image = asyncio.run(_client_with(blink).snapshot("123"))

    assert image == b"neues-bild"
    assert cam.snap_calls == 1
    # Erst nach dem zweiten Refresh war das Bild da - vorher darf nicht abgebrochen werden.
    assert blink.refresh_calls == [True, True]


def test_snapshot_raises_instead_of_returning_the_stale_image():
    cam = _FakeSnapshotCamera(changes_after_refreshes=None)
    blink = _FakeBlink(cam)

    with pytest.raises(TimeoutError):
        asyncio.run(_client_with(blink).snapshot("123"))

    assert len(blink.refresh_calls) == blink_client.SNAPSHOT_MAX_POLLS


def test_snapshot_without_login_is_rejected():
    client = BlinkClient(data_dir=".", camera_name="Haustuer")

    with pytest.raises(blink_client.BlinkNotLoggedInError):
        asyncio.run(client.snapshot("123"))


def test_snapshot_of_unknown_camera_raises_key_error():
    blink = _FakeBlink(_FakeSnapshotCamera(changes_after_refreshes=1))

    with pytest.raises(KeyError):
        asyncio.run(_client_with(blink).snapshot("999"))
