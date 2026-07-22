"""Regressionsschutz fuer die Backend-URLs.

Das Backend laeuft unter dem Kontextpfad /api. Ohne diesen Praefix antwortet es mit
404 - dadurch war die komplette Richtung Sidecar -> Backend still kaputt: Heartbeat,
Erkennungs-Webhook und der Embedding-Pull beim Start liefen alle ins Leere.
"""
import pytest

from app import backend_client, config


@pytest.mark.parametrize("path", ["/heartbeat", "/recognitions", "/embeddings"])
def test_url_contains_api_context_path(path):
    assert backend_client.url(path).endswith(f"/api/v1/vision{path}")


def test_url_uses_configured_backend(monkeypatch):
    monkeypatch.setattr(config, "BACKEND_URL", "http://backend:8080")
    assert backend_client.url("/heartbeat") == "http://backend:8080/api/v1/vision/heartbeat"


def test_trailing_slash_in_backend_url_does_not_double_up(monkeypatch):
    monkeypatch.setattr(config, "BACKEND_URL", "http://backend:8080/")
    assert backend_client.url("/heartbeat") == "http://backend:8080/api/v1/vision/heartbeat"
