"""HTTP-Client Richtung Spring-Backend (Webhook, Heartbeat, Embedding-Pull)."""
import base64
import logging

import httpx

from app import config

log = logging.getLogger(__name__)


async def post_recognition(persons: list[dict], unknown_faces: int, thumbnail: bytes | None) -> None:
    payload = {
        "persons": persons,
        "unknownFaces": unknown_faces,
        "thumbnailBase64": base64.b64encode(thumbnail).decode() if thumbnail else None,
    }
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(f"{config.BACKEND_URL}/v1/vision/recognitions", json=payload)
        response.raise_for_status()


async def post_heartbeat() -> None:
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.post(f"{config.BACKEND_URL}/v1/vision/heartbeat")
        response.raise_for_status()


async def fetch_embeddings() -> list[dict]:
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(f"{config.BACKEND_URL}/v1/vision/embeddings")
        response.raise_for_status()
        return response.json()
