"""HTTP-Client Richtung Spring-Backend (Webhook, Heartbeat, Embedding-Pull)."""
import base64
import logging

import httpx

from app import config

log = logging.getLogger(__name__)

# Das Backend laeuft unter dem Kontextpfad /api (server.servlet.context-path in
# application.properties) — ohne diesen Praefix antwortet es mit 404. BACKEND_URL
# enthaelt nur Host und Port, der Kontextpfad gehoert zur Anwendung und steht hier.
API_PREFIX = "/api/v1/vision"

# Die Bewegungsmeldung ist kein Vision-Ereignis (keine Gesichtserkennung im
# Spiel) und haengt deshalb unter einem eigenen Praefix.
BLINK_API_PREFIX = "/api/v1/blink"


def url(path: str) -> str:
    """Vollstaendige Backend-URL fuer einen Vision-Endpunkt, z. B. '/heartbeat'."""
    return f"{config.BACKEND_URL.rstrip('/')}{API_PREFIX}{path}"


def blink_url(path: str) -> str:
    """Backend-URL fuer einen Blink-Endpunkt (gleicher /api-Kontextpfad wie Vision)."""
    return f"{config.BACKEND_URL.rstrip('/')}{BLINK_API_PREFIX}{path}"


def _headers() -> dict:
    """X-API-Token fuer das Backend; leer konfiguriert -> kein Header (Dev ohne Auth)."""
    return {"X-API-Token": config.API_TOKEN} if config.API_TOKEN else {}


async def post_recognition(persons: list[dict], unknown_faces: int, thumbnail: bytes | None) -> None:
    payload = {
        "persons": persons,
        "unknownFaces": unknown_faces,
        "thumbnailBase64": base64.b64encode(thumbnail).decode() if thumbnail else None,
    }
    async with httpx.AsyncClient(timeout=30, headers=_headers()) as client:
        response = await client.post(url("/recognitions"), json=payload)
        response.raise_for_status()


async def post_heartbeat() -> None:
    async with httpx.AsyncClient(timeout=10, headers=_headers()) as client:
        response = await client.post(url("/heartbeat"))
        response.raise_for_status()


async def post_motion(events: list[dict]) -> None:
    """Meldet neue Local-Storage-Clips als Bewegungen. Wirft bei Fehlschlag —
    der Waechter behaelt daraufhin seine Marke und wiederholt im naechsten Zyklus."""
    async with httpx.AsyncClient(timeout=30, headers=_headers()) as client:
        response = await client.post(blink_url("/motion"), json=events)
        response.raise_for_status()


async def fetch_embeddings() -> list[dict]:
    async with httpx.AsyncClient(timeout=30, headers=_headers()) as client:
        response = await client.get(url("/embeddings"))
        response.raise_for_status()
        return response.json()
