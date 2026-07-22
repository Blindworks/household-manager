"""Tests fuer die Clip-Dedupe: jeder Blink-Clip darf nur einmal analysiert werden,
auch ueber einen Neustart des Sidecars hinweg."""
from app.poller import ClipDedupe


def test_new_clip_ids_are_reported_once(tmp_path):
    state = tmp_path / "state.json"
    dedupe = ClipDedupe(state_file=str(state))

    assert dedupe.is_new("clip-1") is True
    dedupe.mark_processed("clip-1")
    assert dedupe.is_new("clip-1") is False


def test_state_survives_restart(tmp_path):
    state = tmp_path / "state.json"
    ClipDedupe(state_file=str(state)).mark_processed("clip-1")

    reloaded = ClipDedupe(state_file=str(state))

    assert reloaded.is_new("clip-1") is False
    assert reloaded.is_new("clip-2") is True


def test_old_ids_are_pruned(tmp_path):
    state = tmp_path / "state.json"
    dedupe = ClipDedupe(state_file=str(state), max_ids=3)

    for i in range(5):
        dedupe.mark_processed(f"clip-{i}")

    assert dedupe.is_new("clip-0") is True   # rausgealtert
    assert dedupe.is_new("clip-4") is False


def test_corrupt_state_file_is_ignored(tmp_path):
    state = tmp_path / "state.json"
    state.write_text("kein json")

    dedupe = ClipDedupe(state_file=str(state))

    assert dedupe.is_new("clip-1") is True
