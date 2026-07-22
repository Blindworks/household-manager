"""Tests fuer die Session-Persistenz des Blink-Clients.

Sicherheitskritisch: blinkpys eigenes ``blink.save()`` wuerde ``username`` UND
``password`` im Klartext ins Volume schreiben (``Auth.login_attributes`` liefert
das komplette data-Dict). ``BlinkClient._save_session()`` filtert beide ueber
SECRET_KEYS heraus. Faellt dieser Filter weg, liegen die Zugangsdaten auf der
Platte - genau das sichern diese Tests ab.
"""
import json

from app.blink_client import SECRET_KEYS, BlinkClient

USERNAME = "bewohner@example.invalid"
PASSWORD = "sehr-geheimes-blink-passwort"

LOGIN_ATTRIBUTES = {
    "username": USERNAME,
    "password": PASSWORD,
    "token": "auth-token-xyz",
    "host": "rest-prde.immedia-semi.com",
    "region_id": "prde",
    "account_id": 4711,
    "client_id": 1234,
    "device_id": "blink-vision",
}


class _FakeAuth:
    """Minimaler Ersatz fuer blinkpy.auth.Auth - nur login_attributes wird genutzt."""

    def __init__(self, attributes: dict):
        self.login_attributes = attributes


class _FakeBlink:
    def __init__(self, attributes: dict):
        self.auth = _FakeAuth(attributes)


def _client(tmp_path, attributes: dict | None = None) -> BlinkClient:
    client = BlinkClient(data_dir=str(tmp_path / "data"), camera_name="Haustuer")
    client._blink = _FakeBlink(dict(LOGIN_ATTRIBUTES if attributes is None else attributes))
    return client


def _session_file(tmp_path):
    return tmp_path / "data" / "blink-session.json"


def test_secret_keys_cover_username_and_password():
    assert set(SECRET_KEYS) == {"username", "password"}


def test_saved_file_contains_no_credentials_as_raw_text(tmp_path):
    """Rohtext-Pruefung: faengt auch Zugangsdaten, die (etwa nach einem Umbau auf
    verschachtelte Strukturen) in einem ANDEREN Feld landen und einer reinen
    Schluessel-Pruefung entgehen wuerden."""
    client = _client(tmp_path)

    client._save_session()

    raw = _session_file(tmp_path).read_text(encoding="utf-8")
    assert PASSWORD not in raw
    assert USERNAME not in raw
    assert "password" not in raw
    assert "username" not in raw


def test_saved_file_keeps_all_non_secret_fields(tmp_path):
    client = _client(tmp_path)

    client._save_session()

    saved = json.loads(_session_file(tmp_path).read_text(encoding="utf-8"))
    assert saved == {k: v for k, v in LOGIN_ATTRIBUTES.items() if k not in ("username", "password")}
    assert "username" not in saved
    assert "password" not in saved
    assert saved["token"] == "auth-token-xyz"
    assert saved["region_id"] == "prde"


def test_session_is_written_when_data_dir_does_not_exist_yet(tmp_path):
    """data_dir existiert beim ersten Login noch nicht (frisches Volume)."""
    client = _client(tmp_path)
    assert not (tmp_path / "data").exists()

    client._save_session()

    assert _session_file(tmp_path).exists()


def test_without_active_login_nothing_is_written(tmp_path):
    client = BlinkClient(data_dir=str(tmp_path / "data"), camera_name="Haustuer")

    client._save_session()

    assert not _session_file(tmp_path).exists()


def test_saved_session_can_be_restored_as_auth_dict(tmp_path):
    """Die gefilterte Datei muss weiterhin ein gueltiges Auth-data-Dict sein -
    sonst waere die Session nach einem Neustart wertlos."""
    client = _client(tmp_path)

    client._save_session()

    restored = json.loads(_session_file(tmp_path).read_text(encoding="utf-8"))
    assert isinstance(restored, dict)
    assert restored["account_id"] == 4711
    assert restored["client_id"] == 1234
