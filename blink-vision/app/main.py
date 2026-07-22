"""blink-vision-Sidecar: Blink-Clips -> Gesichtserkennung -> Backend-Webhook."""
import asyncio
import base64
import logging
from contextlib import asynccontextmanager, suppress

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app import backend_client, config
from app.analyzer import FaceAnalyzer
from app.blink_client import BlinkClient
from app.persons import PersonStore
from app.poller import Poller

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

blink = BlinkClient(config.DATA_DIR, config.CAMERA_NAME)
persons = PersonStore()
analyzer: FaceAnalyzer | None = None
poller: Poller | None = None


class LoginRequest(BaseModel):
    username: str
    password: str


class VerifyRequest(BaseModel):
    code: str


class EmbeddingRequest(BaseModel):
    imageBase64: str  # noqa: N815 - Feldname ist Teil des Backend-Vertrags


async def _run_sidecar() -> None:
    """Hintergrund-Start: laedt Personen und Modell und pollt danach dauerhaft.

    Bewusst NICHT im Startup-Pfad: der erste FaceAnalyzer() laedt das Modell
    buffalo_s (~124 MB) und wuerde den Serverstart - und damit jeden
    Container-Healthcheck auf /health - minutenlang blockieren."""
    global analyzer, poller
    try:
        persons.replace(await backend_client.fetch_embeddings())
    except Exception as ex:
        log.warning("Embeddings vom Backend nicht ladbar (kommt per Push): %s", ex)
    await blink.try_restore_session()
    analyzer = await asyncio.to_thread(FaceAnalyzer)
    poller = Poller(blink, analyzer, persons)
    await poller.run_forever()


@asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(_supervised_sidecar())
    yield
    task.cancel()
    with suppress(asyncio.CancelledError):
        await task
    await blink.close()


async def _supervised_sidecar() -> None:
    """Sorgt dafuer, dass ein Absturz des Hintergrundtasks sichtbar wird, statt
    lautlos in einem nie abgefragten Task-Ergebnis zu verschwinden."""
    try:
        await _run_sidecar()
    except asyncio.CancelledError:
        raise
    except Exception:
        log.exception("Sidecar-Hintergrundtask beendet - es werden keine Clips mehr geprueft")


app = FastAPI(title="blink-vision", lifespan=lifespan)


def _require_analyzer() -> FaceAnalyzer:
    if analyzer is None:
        raise HTTPException(status_code=503, detail={
            "error": "Gesichtserkennung startet noch (Modell wird geladen) - bitte gleich erneut versuchen."})
    return analyzer


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/status")
async def status():
    return {
        "loggedIn": blink.logged_in,
        "pending2fa": blink.pending_2fa,
        "cameraFound": blink.camera_name() is not None,
        "cameraName": blink.camera_name(),
        "analyzerReady": analyzer is not None,
        "lastPollAt": poller.last_poll_at if poller else None,
    }


@app.post("/auth/login")
async def login(request: LoginRequest):
    try:
        await blink.login(request.username, request.password)
    except Exception as ex:
        raise HTTPException(status_code=502, detail={"error": f"Blink-Login fehlgeschlagen: {ex}"})
    return {"pending2fa": blink.pending_2fa}


@app.post("/auth/verify")
async def verify(request: VerifyRequest):
    try:
        await blink.verify(request.code)
    except Exception as ex:
        raise HTTPException(status_code=502, detail={"error": f"2FA fehlgeschlagen: {ex}"})
    return {"loggedIn": blink.logged_in}


@app.post("/embeddings")
async def embeddings(request: EmbeddingRequest):
    face_analyzer = _require_analyzer()
    try:
        image = base64.b64decode(request.imageBase64)
    except Exception:
        raise HTTPException(status_code=400, detail={"error": "Foto ist kein gueltiges Base64."})
    result = await asyncio.to_thread(face_analyzer.embeddings_from_image, image)
    if not result:
        return {"embedding": None, "faces": 0}
    if len(result) > 1:
        raise HTTPException(status_code=400,
                            detail={"error": "Mehrere Gesichter auf dem Referenzfoto - bitte Einzelportraet."})
    return {"embedding": [float(x) for x in result[0]], "faces": 1}


@app.put("/persons")
async def put_persons(payload: list[dict]):
    persons.replace(payload)
    return {"count": len(payload)}
