from app.cooldown import Cooldown


def test_first_report_allowed_then_blocked_within_window():
    t = [100.0]
    cd = Cooldown(seconds=120, clock=lambda: t[0])
    assert cd.allow("benedikt") is True
    t[0] = 150.0
    assert cd.allow("benedikt") is False


def test_allowed_again_after_window():
    t = [100.0]
    cd = Cooldown(seconds=120, clock=lambda: t[0])
    assert cd.allow("benedikt") is True
    t[0] = 221.0
    assert cd.allow("benedikt") is True


def test_keys_are_independent():
    t = [100.0]
    cd = Cooldown(seconds=120, clock=lambda: t[0])
    assert cd.allow("benedikt") is True
    assert cd.allow("partnerin") is True
