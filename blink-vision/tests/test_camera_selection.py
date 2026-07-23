"""Auswahl der Tuerkamera.

Realer Fehlerfall, der diese Tests ausgeloest hat: Das Konto hatte zwei Kameras
('Wohnzimmer ' und 'Frontdoor'). Ohne gesetzten Namen nahm der Sidecar schlicht die
erste - also die Innenraumkamera - und verwarf dadurch alle Tuerklingel-Clips.
Ausserdem: Blink-Kameranamen enthalten in der Praxis gern ein Rand-Leerzeichen.
"""
from app.blink_client import BlinkClient


class FakeCamera:
    def __init__(self, camera_type=""):
        self.camera_type = camera_type


def client_with(cameras, configured=""):
    client = BlinkClient(data_dir=".", camera_name=configured)
    client._blink = type("FakeBlink", (), {"cameras": cameras})()
    return client


def test_prefers_doorbell_over_indoor_camera():
    client = client_with({"Wohnzimmer ": FakeCamera(), "Frontdoor": FakeCamera("doorbell")})
    assert client.camera_name() == "Frontdoor"


def test_configured_name_wins_over_doorbell():
    client = client_with({"Wohnzimmer ": FakeCamera(), "Frontdoor": FakeCamera("doorbell")},
                         configured="Wohnzimmer ")
    assert client.camera_name() == "Wohnzimmer "


def test_configured_name_matches_despite_trailing_space():
    client = client_with({"Wohnzimmer ": FakeCamera()}, configured="Wohnzimmer")
    assert client.camera_name() == "Wohnzimmer "


def test_single_camera_is_used_even_without_doorbell_type():
    client = client_with({"Nurdiese": FakeCamera()})
    assert client.camera_name() == "Nurdiese"


def test_ambiguous_without_doorbell_returns_none():
    client = client_with({"Kueche": FakeCamera(), "Garten": FakeCamera()})
    assert client.camera_name() is None


def test_several_doorbells_return_none():
    client = client_with({"Vorne": FakeCamera("doorbell"), "Hinten": FakeCamera("doorbell")})
    assert client.camera_name() is None


def test_unknown_configured_name_falls_back_to_doorbell():
    client = client_with({"Wohnzimmer ": FakeCamera(), "Frontdoor": FakeCamera("doorbell")},
                         configured="Tippfehler")
    assert client.camera_name() == "Frontdoor"


def test_no_cameras_returns_none():
    assert client_with({}).camera_name() is None
