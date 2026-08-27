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


def test_multiple_new_clips_all_fire_and_mark_advances_to_newest():
    """Eine bekannte Kamera mit mehreren neuen Clips: alle melden, Marke auf den neuesten.

    Bliebe die Marke auf einem aelteren Clip stehen, wuerde der neuere im
    naechsten Zyklus ein zweites Mal gemeldet.
    """
    source, sink = _FakeSource(), _FakeSink()
    source.snapshots = [
        [_entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "13", "2026-08-27T13:00:00"),
         _entry("1", "12", "2026-08-27T12:00:00"),
         _entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "13", "2026-08-27T13:00:00"),
         _entry("1", "12", "2026-08-27T12:00:00"),
         _entry("1", "10", "2026-08-27T10:00:00")],
    ]
    watcher = MotionWatcher(source, sink)
    _run(watcher); _run(watcher); _run(watcher)
    assert len(sink.calls) == 1
    assert [e["clipId"] for e in sink.calls[0]] == ["13", "12"]


def test_failed_webhook_retries_together_with_a_further_clip():
    """Scheitert der Webhook, steht im naechsten Zyklus der alte PLUS der neue Clip an."""
    source, sink = _FakeSource(), _FakeSink(fail_times=1)
    source.snapshots = [
        [_entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "12", "2026-08-27T12:00:00"), _entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "13", "2026-08-27T13:00:00"),
         _entry("1", "12", "2026-08-27T12:00:00"),
         _entry("1", "10", "2026-08-27T10:00:00")],
        [_entry("1", "13", "2026-08-27T13:00:00"),
         _entry("1", "12", "2026-08-27T12:00:00"),
         _entry("1", "10", "2026-08-27T10:00:00")],
    ]
    watcher = MotionWatcher(source, sink)
    _run(watcher); _run(watcher); _run(watcher); _run(watcher)
    assert len(sink.calls) == 1
    assert [e["clipId"] for e in sink.calls[0]] == ["13", "12"]


def test_microseconds_in_timestamp_do_not_break_the_comparison():
    """datetime.isoformat() haengt Mikrosekunden nur an, wenn sie ungleich 0 sind.

    Im selben Manifest stehen deshalb Zeitstempel unterschiedlicher Laenge
    nebeneinander. Der String-Vergleich traegt das, weil der Teil bis zur
    Sekunde feste Breite hat und '.' unter allen Ziffern sortiert.
    """
    source, sink = _FakeSource(), _FakeSink()
    source.snapshots = [
        [_entry("1", "10", "2026-08-27T10:00:00.123456")],
        [_entry("1", "11", "2026-08-27T10:00:00.987654"),
         _entry("1", "10", "2026-08-27T10:00:00.123456")],
        [_entry("1", "12", "2026-08-27T10:00:01"),
         _entry("1", "11", "2026-08-27T10:00:00.987654"),
         _entry("1", "10", "2026-08-27T10:00:00.123456")],
    ]
    watcher = MotionWatcher(source, sink)
    _run(watcher); _run(watcher); _run(watcher)
    assert [[e["clipId"] for e in call] for call in sink.calls] == [["11"], ["12"]]
