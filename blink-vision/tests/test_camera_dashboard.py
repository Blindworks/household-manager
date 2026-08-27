"""Kamera-Dashboard: Mapping, Lookup und die Dashboard-Methoden des BlinkClient
(ohne echte Blink-Cloud). Fakes per SimpleNamespace/Miniklassen."""
import asyncio
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

import pytest

from app import blink_client
from app.blink_client import (BlinkClient, _camera_summary, _clip_summary,
                              _find_in_syncs)


def _cam(camera_id="123", camera_type="doorbell", arm=True, battery="ok"):
    return SimpleNamespace(camera_id=camera_id, camera_type=camera_type,
                           arm=arm, battery=battery)


# ==================== Reine Hilfsfunktionen ====================


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


# ==================== Fakes fuer die Dashboard-Methoden ====================


class _FakeCamera:
    """Kamera, deren Thumbnail-URL sich nach `fresh_after_refreshes` Refreshes
    aendert - so wie es die echte Cloud tut, wenn der Upload durch ist.
    `None` = die URL aendert sich nie (Schnappschuss laeuft ins Zeitbudget)."""

    def __init__(self, camera_id="123", camera_type="doorbell", arm=True,
                 battery="ok", cached_image=b"altes-bild", fresh_after_refreshes=None):
        self.camera_id = camera_id
        self.camera_type = camera_type
        self.arm = arm
        self.battery = battery
        self.thumbnail = "https://blink/alt.jpg"
        self.image_from_cache = cached_image
        self._fresh_after = fresh_after_refreshes
        self._refreshes = 0
        self.snap_calls = 0
        self.armed_to = None

    async def snap_picture(self):
        self.snap_calls += 1

    async def async_arm(self, value):
        self.armed_to = value

    def on_refresh(self):
        self._refreshes += 1
        if self._fresh_after is not None and self._refreshes >= self._fresh_after:
            self.thumbnail = "https://blink/neu.jpg"
            self.image_from_cache = b"neues-bild"


class _FakeItem:
    def __init__(self, clip_id, camera_name, minute=0, download_ok=True):
        self.id = clip_id
        self.name = camera_name
        self.created_at = datetime(2026, 8, 27, 14, minute, 0)
        self.size = 1234
        self.prepared = False
        self.downloaded_to = None
        self._download_ok = download_ok

    async def prepare_download(self, blink):
        self.prepared = True

    async def download_video(self, blink, file_name):
        self.downloaded_to = file_name
        if self._download_ok:
            Path(file_name).write_bytes(b"video")
        return self._download_ok


class _FakeSync:
    def __init__(self, cameras, arm=True, local_storage=True, manifest=()):
        self.cameras = cameras
        self.arm = arm
        self.local_storage = local_storage
        # Das echte Manifest ist AUFSTEIGEND nach created_at sortiert.
        self._local_storage = {"manifest": list(manifest)}
        self.refresh_calls = 0
        self.armed_to = None

    async def refresh(self, force_cache=False):
        self.refresh_calls += 1

    async def async_arm(self, value):
        self.armed_to = value


class _FakeBlink:
    def __init__(self, syncs):
        self.sync = syncs
        # blinkpy fuellt blink.cameras aus allen Sync-Modulen (blinkpy.py:192).
        self.cameras = {name: cam for sync in syncs.values()
                        for name, cam in sync.cameras.items()}
        self.refresh_calls: list[bool] = []

    async def refresh(self, force=False, force_cache=False):
        self.refresh_calls.append(force)
        for sync in self.sync.values():
            for cam in sync.cameras.values():
                on_refresh = getattr(cam, "on_refresh", None)
                if on_refresh is not None:
                    on_refresh()
        return True


def _client_with(blink) -> BlinkClient:
    client = BlinkClient(data_dir=".", camera_name="Haustuer")
    client._blink = blink
    return client


def _single_sync_blink(cam=None, **sync_kwargs) -> _FakeBlink:
    cam = cam if cam is not None else _FakeCamera()
    return _FakeBlink({"Zuhause": _FakeSync({"Haustuer": cam}, **sync_kwargs)})


@pytest.fixture(autouse=True)
def _no_real_sleeping(monkeypatch):
    monkeypatch.setattr(blink_client, "SNAPSHOT_POLL_SECONDS", 0)


# ==================== list_cameras ====================


def test_list_cameras_maps_every_camera_of_every_sync():
    blink = _FakeBlink({
        "Zuhause": _FakeSync({"Haustuer": _FakeCamera(camera_id="1")}, arm=True),
        "Ferienhaus": _FakeSync({"Innen": _FakeCamera(camera_id="2", camera_type="mini",
                                                      arm=False, battery=None)}, arm=False),
    })

    cameras = asyncio.run(_client_with(blink).list_cameras())

    assert cameras == [
        {"cameraId": "1", "name": "Haustuer", "type": "doorbell", "armed": True,
         "battery": "ok", "syncName": "Zuhause", "syncArmed": True},
        {"cameraId": "2", "name": "Innen", "type": "mini", "armed": False,
         "battery": None, "syncName": "Ferienhaus", "syncArmed": False},
    ]


def test_list_cameras_uses_the_throttled_refresh_by_default():
    blink = _single_sync_blink()

    asyncio.run(_client_with(blink).list_cameras())

    assert blink.refresh_calls == [False]


def test_list_cameras_can_force_a_fresh_refresh():
    """Nach einem Schaltbefehl muss die Liste erzwungen frisch geholt werden -
    async_arm() setzt cam.arm nicht lokal, und der normale refresh() ist 30 s
    gedrosselt. Ohne force saehe der Nutzer seinen eigenen Schaltbefehl nicht."""
    blink = _single_sync_blink()

    asyncio.run(_client_with(blink).list_cameras(force=True))

    assert blink.refresh_calls == [True]


def test_list_cameras_without_login_is_rejected():
    client = BlinkClient(data_dir=".", camera_name="Haustuer")

    with pytest.raises(blink_client.BlinkNotLoggedInError):
        asyncio.run(client.list_cameras())


# ==================== Scharf schalten ====================


def test_set_camera_armed_switches_the_camera_found_by_id():
    cam = _FakeCamera(camera_id="7")
    blink = _FakeBlink({"Zuhause": _FakeSync({"Haustuer": cam})})

    asyncio.run(_client_with(blink).set_camera_armed("7", False))

    assert cam.armed_to is False


def test_set_camera_armed_raises_for_unknown_camera():
    with pytest.raises(KeyError):
        asyncio.run(_client_with(_single_sync_blink()).set_camera_armed("999", True))


def test_set_sync_armed_switches_the_whole_module():
    blink = _single_sync_blink()

    asyncio.run(_client_with(blink).set_sync_armed("Zuhause", True))

    assert blink.sync["Zuhause"].armed_to is True


def test_set_sync_armed_raises_for_unknown_sync():
    with pytest.raises(KeyError):
        asyncio.run(_client_with(_single_sync_blink()).set_sync_armed("Ferienhaus", True))


def test_set_sync_armed_without_login_is_rejected():
    client = BlinkClient(data_dir=".", camera_name="Haustuer")

    with pytest.raises(blink_client.BlinkNotLoggedInError):
        asyncio.run(client.set_sync_armed("Zuhause", True))


# ==================== Standbild aus dem Cache ====================


def test_thumbnail_returns_the_cached_image_without_refreshing():
    blink = _single_sync_blink(_FakeCamera(cached_image=b"bild"))

    assert asyncio.run(_client_with(blink).thumbnail("123")) == b"bild"
    assert blink.refresh_calls == []


def test_thumbnail_refreshes_once_when_the_cache_is_empty():
    cam = _FakeCamera(cached_image=None, fresh_after_refreshes=1)
    blink = _single_sync_blink(cam)

    assert asyncio.run(_client_with(blink).thumbnail("123")) == b"neues-bild"
    assert blink.refresh_calls == [True]


def test_thumbnail_raises_when_no_image_exists_at_all():
    blink = _single_sync_blink(_FakeCamera(cached_image=None))

    with pytest.raises(RuntimeError):
        asyncio.run(_client_with(blink).thumbnail("123"))


def test_thumbnail_raises_for_unknown_camera():
    with pytest.raises(KeyError):
        asyncio.run(_client_with(_single_sync_blink()).thumbnail("999"))


# ==================== Schnappschuss-Warteschleife ====================
#
# Der trickreichste Teil des Dashboards: snap_picture() weist die Kamera nur an,
# ein Bild zu machen - hochgeladen ist es erst Sekunden spaeter. Ohne die
# Warteschleife lieferte der Knopf still das ALTE Bild aus. Genau das sichern die
# folgenden Tests ab; sie schlafen dabei nicht wirklich (SNAPSHOT_POLL_SECONDS=0).


def test_snapshot_returns_new_image_once_thumbnail_url_changed():
    cam = _FakeCamera(fresh_after_refreshes=2)
    blink = _single_sync_blink(cam)

    image = asyncio.run(_client_with(blink).snapshot("123"))

    assert image == b"neues-bild"
    assert cam.snap_calls == 1
    # Erst nach dem zweiten Refresh war das Bild da - vorher darf nicht abgebrochen werden.
    assert blink.refresh_calls == [True, True]


def test_snapshot_raises_instead_of_returning_the_stale_image():
    cam = _FakeCamera(fresh_after_refreshes=None)
    blink = _single_sync_blink(cam)

    with pytest.raises(TimeoutError):
        asyncio.run(_client_with(blink).snapshot("123"))

    assert len(blink.refresh_calls) == blink_client.SNAPSHOT_MAX_POLLS


def test_snapshot_without_login_is_rejected():
    client = BlinkClient(data_dir=".", camera_name="Haustuer")

    with pytest.raises(blink_client.BlinkNotLoggedInError):
        asyncio.run(client.snapshot("123"))


def test_snapshot_of_unknown_camera_raises_key_error():
    with pytest.raises(KeyError):
        asyncio.run(_client_with(_single_sync_blink()).snapshot("999"))


# ==================== Clips ====================


def _blink_with_two_locations() -> _FakeBlink:
    """Zwei Sync-Module mit GLEICHNAMIGEN Kameras - der Fall, fuer den die
    Aufloesung ueber die stabile camera_id ueberhaupt existiert."""
    return _FakeBlink({
        "Zuhause": _FakeSync(
            {"Haustuer": _FakeCamera(camera_id="1")},
            manifest=[_FakeItem(10, "Haustuer", minute=1)]),
        "Ferienhaus": _FakeSync(
            {"Haustuer": _FakeCamera(camera_id="2")},
            manifest=[_FakeItem(20, "Haustuer", minute=2)]),
    })


def test_list_clips_stays_within_the_sync_module_of_that_camera():
    """Beide Kameras heissen 'Haustuer'. Wuerde ueber alle Sync-Module hinweg
    per Name gesucht, kaemen die Clips des Ferienhauses mit zurueck."""
    blink = _blink_with_two_locations()

    clips = asyncio.run(_client_with(blink).list_clips("1"))

    assert [clip["clipId"] for clip in clips] == ["10"]


def test_list_clips_does_not_refresh_foreign_sync_modules():
    blink = _blink_with_two_locations()

    asyncio.run(_client_with(blink).list_clips("1"))

    assert blink.sync["Zuhause"].refresh_calls == 1
    assert blink.sync["Ferienhaus"].refresh_calls == 0


def test_list_clips_returns_newest_first_and_only_that_camera():
    cam = _FakeCamera(camera_id="1")
    blink = _FakeBlink({"Zuhause": _FakeSync(
        {"Haustuer": cam, "Garten": _FakeCamera(camera_id="2")},
        manifest=[_FakeItem(1, "Haustuer", minute=1),
                  _FakeItem(2, "Garten", minute=2),
                  _FakeItem(3, "Haustuer", minute=3)])})

    clips = asyncio.run(_client_with(blink).list_clips("1"))

    assert [clip["clipId"] for clip in clips] == ["3", "1"]


def test_list_clips_is_empty_without_local_storage():
    blink = _single_sync_blink(local_storage=False,
                               manifest=[_FakeItem(1, "Haustuer")])

    assert asyncio.run(_client_with(blink).list_clips("123")) == []


def test_list_clips_raises_for_unknown_camera():
    with pytest.raises(KeyError):
        asyncio.run(_client_with(_single_sync_blink()).list_clips("999"))


def test_fetch_clip_downloads_into_the_cache_directory(tmp_path):
    item = _FakeItem(10, "Haustuer")
    blink = _single_sync_blink(manifest=[item])

    path = asyncio.run(_client_with(blink).fetch_clip("123", "10", str(tmp_path / "cache")))

    assert path == str(tmp_path / "cache" / "clip-10.mp4")
    assert Path(path).exists()
    assert item.prepared is True


def test_fetch_clip_serves_an_already_cached_file_without_downloading(tmp_path):
    item = _FakeItem(10, "Haustuer")
    blink = _single_sync_blink(manifest=[item])
    cache = tmp_path / "cache"
    cache.mkdir()
    (cache / "clip-10.mp4").write_bytes(b"schon-da")

    path = asyncio.run(_client_with(blink).fetch_clip("123", "10", str(cache)))

    assert Path(path).read_bytes() == b"schon-da"
    assert item.downloaded_to is None
    assert blink.sync["Zuhause"].refresh_calls == 0


def test_fetch_clip_does_not_reach_into_another_sync_module(tmp_path):
    """Clip 20 liegt im Ferienhaus; ueber die Kamera 'Zuhause' darf er nicht
    erreichbar sein, obwohl beide Kameras gleich heissen."""
    blink = _blink_with_two_locations()

    with pytest.raises(KeyError):
        asyncio.run(_client_with(blink).fetch_clip("1", "20", str(tmp_path)))


def test_fetch_clip_raises_for_unknown_clip(tmp_path):
    blink = _single_sync_blink(manifest=[_FakeItem(10, "Haustuer")])

    with pytest.raises(KeyError):
        asyncio.run(_client_with(blink).fetch_clip("123", "999", str(tmp_path)))


def test_fetch_clip_raises_when_the_download_fails(tmp_path):
    blink = _single_sync_blink(manifest=[_FakeItem(10, "Haustuer", download_ok=False)])

    with pytest.raises(RuntimeError):
        asyncio.run(_client_with(blink).fetch_clip("123", "10", str(tmp_path)))


# ==================== manifest_snapshot ====================
#
# Datenquelle des Bewegungs-Waechters: nur Metadaten, kein Download. Deshalb
# pruefen die Tests hier ausdruecklich AUCH, dass nichts heruntergeladen wird -
# ein Griff in den Download-Pfad waere ein Eingriff in die Gesichtserkennung.


def _snapshot_blink() -> _FakeBlink:
    """Zwei Sync-Module: 'Zuhause' (Local Storage, 2 Kameras) und 'Garage' (ohne)."""
    return _FakeBlink({
        "Zuhause": _FakeSync(
            {"Frontdoor": _FakeCamera(camera_id="1"),
             "Wohnzimmer": _FakeCamera(camera_id="2")},
            manifest=[_FakeItem(10, "Frontdoor", minute=0),
                      _FakeItem(11, "Wohnzimmer", minute=10),
                      _FakeItem(12, "Frontdoor", minute=20)]),
        "Garage": _FakeSync({"Aussen": _FakeCamera(camera_id="3")},
                            arm=False, local_storage=False,
                            manifest=[_FakeItem(30, "Aussen", minute=30)]),
    })


def test_manifest_snapshot_resolves_camera_ids_and_sorts_newest_first():
    snapshot = asyncio.run(_client_with(_snapshot_blink()).manifest_snapshot())

    assert [entry["clipId"] for entry in snapshot] == ["12", "11", "10"]
    assert snapshot[0] == {
        "cameraId": "1",
        "cameraName": "Frontdoor",
        "clipId": "12",
        "createdAt": "2026-08-27T14:20:00",
    }


def test_manifest_snapshot_skips_clips_of_unknown_cameras():
    """Ein Clip einer inzwischen entfernten Kamera hat keine camera_id mehr -
    er wird verworfen statt mit geratener Zuordnung gemeldet."""
    blink = _snapshot_blink()
    blink.sync["Zuhause"]._local_storage["manifest"].append(
        _FakeItem(99, "GeloeschteKamera", minute=30))

    snapshot = asyncio.run(_client_with(blink).manifest_snapshot())

    assert all(entry["clipId"] != "99" for entry in snapshot)


def test_manifest_snapshot_ignores_sync_modules_without_local_storage():
    """Ohne Local Storage gibt es kein Manifest - der Clip der Garage darf
    nicht auftauchen, obwohl er im Fake hinterlegt ist."""
    snapshot = asyncio.run(_client_with(_snapshot_blink()).manifest_snapshot())

    assert all(entry["cameraId"] != "3" for entry in snapshot)


def test_manifest_snapshot_reads_metadata_only():
    """Kein prepare_download/download_video - der Waechter fasst den
    Download-Pfad der Gesichtserkennung nicht an."""
    blink = _snapshot_blink()
    items = blink.sync["Zuhause"]._local_storage["manifest"]

    asyncio.run(_client_with(blink).manifest_snapshot())

    assert all(not item.prepared and item.downloaded_to is None for item in items)


def test_manifest_snapshot_requires_login():
    client = BlinkClient(data_dir=".", camera_name="")

    with pytest.raises(blink_client.BlinkNotLoggedInError):
        asyncio.run(client.manifest_snapshot())


# ==================== Regressionsschutz fuer fetch_new_clips ====================
#
# fetch_new_clips ist Bestandscode des Gesichtserkennungs-Pfads und war bis zum
# DRY-Umbau auf _manifest_newest_first voellig ungetestet. Die Tests stehen hier
# statt in einer eigenen Datei, weil sie dieselben Fakes brauchen. Sie halten
# genau die Eigenschaften fest, die sich beim Umbau NICHT aendern durften.


def _door_client(blink) -> BlinkClient:
    client = BlinkClient(data_dir=".", camera_name="Haustuer")
    client._blink = blink
    return client


def test_fetch_new_clips_returns_only_new_clips_of_the_door_camera(tmp_path):
    blink = _FakeBlink({"Zuhause": _FakeSync(
        {"Haustuer": _FakeCamera(camera_id="1"), "Garten": _FakeCamera(camera_id="2")},
        manifest=[_FakeItem(1, "Haustuer", minute=1),
                  _FakeItem(2, "Garten", minute=2),
                  _FakeItem(3, "Haustuer", minute=3)])})

    results = asyncio.run(_door_client(blink).fetch_new_clips(lambda _: True, str(tmp_path)))

    # Neueste zuerst, Garten-Clip bleibt aussen vor.
    assert [clip_id for clip_id, _ in results] == ["3", "1"]
    assert all(Path(path).exists() for _, path in results)


def test_fetch_new_clips_respects_the_dedupe_callback(tmp_path):
    blink = _single_sync_blink(manifest=[_FakeItem(1, "Haustuer", minute=1),
                                         _FakeItem(2, "Haustuer", minute=2)])
    seen = {"1"}

    results = asyncio.run(
        _door_client(blink).fetch_new_clips(lambda clip_id: clip_id not in seen, str(tmp_path)))

    assert [clip_id for clip_id, _ in results] == ["2"]


def test_fetch_new_clips_looks_across_all_sync_modules(tmp_path):
    """Bewusster Unterschied zu list_clips: hier wird die Kamera ueber ihren
    NAMEN bestimmt, nicht ueber eine camera_id - also zaehlen alle Module."""
    blink = _FakeBlink({
        "Alt": _FakeSync({"Haustuer": _FakeCamera(camera_id="1")},
                         manifest=[_FakeItem(1, "Haustuer", minute=1)]),
        "Neu": _FakeSync({"Garten": _FakeCamera(camera_id="2")},
                         manifest=[_FakeItem(2, "Haustuer", minute=2)]),
    })

    results = asyncio.run(_door_client(blink).fetch_new_clips(lambda _: True, str(tmp_path)))

    assert sorted(clip_id for clip_id, _ in results) == ["1", "2"]


def test_fetch_new_clips_skips_a_failed_download_instead_of_raising(tmp_path):
    blink = _single_sync_blink(manifest=[_FakeItem(1, "Haustuer", minute=1, download_ok=False),
                                         _FakeItem(2, "Haustuer", minute=2)])

    results = asyncio.run(_door_client(blink).fetch_new_clips(lambda _: True, str(tmp_path)))

    assert [clip_id for clip_id, _ in results] == ["2"]


def test_fetch_new_clips_without_local_storage_returns_nothing(tmp_path):
    blink = _single_sync_blink(local_storage=False,
                               manifest=[_FakeItem(1, "Haustuer")])

    assert asyncio.run(_door_client(blink).fetch_new_clips(lambda _: True, str(tmp_path))) == []
