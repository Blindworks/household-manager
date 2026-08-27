"""Kamera-Dashboard-Endpunkte: Liste, Scharf/Unscharf, Schnappschuss, Clips.

Duenne HTTP-Schicht - alle blinkpy-Zugriffe leben in blink_client.py.
'Nicht angemeldet' ist HTTP 409 (das Backend uebersetzt das in 400 mit
Login-Hinweis; ein 401 wuerde den Nutzer aus seiner Haushalts-Sitzung werfen);
unbekannte Kamera/Clip 404; alles andere - auch der TimeoutError des
Schnappschusses - 502.
"""
import logging
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse, Response

from app import config
from app.blink_client import BlinkClient, BlinkNotLoggedInError

log = logging.getLogger(__name__)

CLIP_CACHE_DIR = str(Path(config.DATA_DIR) / "clip-cache")


def build_router(blink: BlinkClient) -> APIRouter:
    """Fabrik statt Modul-Router: so laesst sich im Test ein gefakter Client
    einsetzen, ohne an der Blink-Cloud zu haengen."""
    router = APIRouter()

    async def _call(coro):
        try:
            return await coro
        except BlinkNotLoggedInError:
            raise HTTPException(status_code=409, detail={"error": "Nicht bei Blink angemeldet."})
        except KeyError as ex:
            raise HTTPException(status_code=404, detail={"error": str(ex)})
        except HTTPException:
            # Muss VOR dem generischen Zweig stehen, sonst wuerde ein bewusst
            # gesetzter Statuscode als 502 nach aussen gehen.
            raise
        except Exception as ex:
            log.warning("Blink-Kamera-Aufruf fehlgeschlagen: %s", ex)
            raise HTTPException(status_code=502, detail={"error": f"Blink-Fehler: {ex}"})

    @router.get("/cameras")
    async def list_cameras(force: bool = False):
        # force=true umgeht die 30-s-Drossel von blinkpy. Noetig nach einer
        # Schaltaktion: async_arm() aendert den lokalen Zustand NICHT, der neue
        # Wert kommt erst mit dem naechsten echten Refresh. Ohne force zeigt das
        # Dashboard direkt nach dem Schalten weiter den alten Zustand.
        return await _call(blink.list_cameras(force=force))

    @router.post("/cameras/{camera_id}/arm")
    async def arm_camera(camera_id: str):
        await _call(blink.set_camera_armed(camera_id, True))
        return {"armed": True}

    @router.post("/cameras/{camera_id}/disarm")
    async def disarm_camera(camera_id: str):
        await _call(blink.set_camera_armed(camera_id, False))
        return {"armed": False}

    @router.post("/system/{sync_name}/arm")
    async def arm_system(sync_name: str):
        await _call(blink.set_sync_armed(sync_name, True))
        return {"armed": True}

    @router.post("/system/{sync_name}/disarm")
    async def disarm_system(sync_name: str):
        await _call(blink.set_sync_armed(sync_name, False))
        return {"armed": False}

    @router.post("/cameras/{camera_id}/snapshot")
    async def snapshot(camera_id: str):
        image = await _call(blink.snapshot(camera_id))
        return Response(content=image, media_type="image/jpeg")

    @router.get("/cameras/{camera_id}/thumbnail")
    async def thumbnail(camera_id: str):
        image = await _call(blink.thumbnail(camera_id))
        return Response(content=image, media_type="image/jpeg")

    @router.get("/cameras/{camera_id}/clips")
    async def clips(camera_id: str):
        return await _call(blink.list_clips(camera_id))

    @router.get("/cameras/{camera_id}/clips/{clip_id}")
    async def clip(camera_id: str, clip_id: str):
        # fetch_clip legt CLIP_CACHE_DIR selbst an (mkdir(parents=True)) - hier
        # ist deshalb kein eigenes Anlegen noetig.
        path = await _call(blink.fetch_clip(camera_id, clip_id, CLIP_CACHE_DIR))
        return FileResponse(path, media_type="video/mp4")

    return router
