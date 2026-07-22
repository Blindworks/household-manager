"""InsightFace-Wrapper: Embeddings aus Bildern/Videoclips (CPU, Modell buffalo_s)."""
import logging
import math
import threading

import cv2
import numpy as np
from insightface.app import FaceAnalysis

log = logging.getLogger(__name__)

FRAME_STEP = 5       # Mindestabstand zwischen zwei analysierten Frames
MAX_FRAMES = 12      # Obergrenze pro Clip
THUMB_WIDTH = 320


class FaceAnalyzer:
    def __init__(self):
        # Nur Detektion + Erkennung: buffalo_s enthaelt zusaetzlich ein 3D-Landmark-
        # (143 MB) und ein Alter/Geschlecht-Modell, die hier niemand braucht und die
        # sonst bei jedem Gesicht mitlaufen wuerden.
        self._app = FaceAnalysis(name="buffalo_s", allowed_modules=["detection", "recognition"],
                                 providers=["CPUExecutionProvider"])
        self._app.prepare(ctx_id=-1, det_size=(640, 640))
        # InsightFace/ONNXRuntime sind nicht als threadsicher dokumentiert. Poller und
        # /embeddings-Endpunkt laufen beide ueber asyncio.to_thread auf demselben
        # Modell - das Lock serialisiert jeden einzelnen Modellaufruf. Es wird
        # bewusst nur um _detect gehalten (nicht um den ganzen Clip), damit ein
        # Referenzfoto nicht hinter einer laufenden Clip-Analyse warten muss.
        self._lock = threading.Lock()

    def _detect(self, image):
        with self._lock:
            return self._app.get(image)

    def embeddings_from_image(self, image_bytes: bytes) -> list[np.ndarray]:
        """Alle Gesichts-Embeddings eines Einzelbilds (fuer Referenzfotos)."""
        img = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            return []
        return [f.normed_embedding for f in self._detect(img)]

    def analyze_clip(self, clip_path: str) -> tuple[list[np.ndarray], bytes | None]:
        """Alle Gesichts-Embeddings ueber ausgewaehlte Frames eines Clips
        plus JPEG-Thumbnail des Frames mit den meisten Gesichtern."""
        embeddings: list[np.ndarray] = []
        best_frame = None
        best_face_count = 0
        capture = cv2.VideoCapture(clip_path)
        try:
            for frame in _selected_frames(capture):
                faces = self._detect(frame)
                embeddings.extend(f.normed_embedding for f in faces)
                if len(faces) > best_face_count:
                    best_face_count = len(faces)
                    best_frame = frame
        finally:
            capture.release()
        return embeddings, _to_thumbnail(best_frame)


def target_frame_indices(total_frames: int) -> list[int]:
    """Bis zu MAX_FRAMES Frame-Indizes, gleichmaessig ueber den GANZEN Clip verteilt.

    Wer auf die Haustuer zulaeuft, ist erst am Clipende gross und frontal im Bild -
    nur den Anfang zu analysieren kostet Erkennungsrate. FRAME_STEP bleibt als
    Mindestabstand erhalten, damit kurze Clips nicht fast identische Frames liefern."""
    if total_frames <= 0:
        return []
    count = min(MAX_FRAMES, math.ceil(total_frames / FRAME_STEP))
    if count <= 1:
        return [0]
    last = total_frames - 1
    return sorted({round(i * last / (count - 1)) for i in range(count)})


def _selected_frames(capture):
    """Frames des Clips in Analyse-Reihenfolge (sequenzielles Lesen, kein Seeking:
    manche Container liefern beim Springen falsche oder gar keine Frames)."""
    total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    targets = target_frame_indices(total_frames)
    if not targets:
        # Container ohne verlaessliche Frameanzahl (0 oder -1): altes Verhalten.
        yield from _every_nth_frame(capture)
        return
    wanted = set(targets)
    index = 0
    while index <= targets[-1]:
        ok, frame = capture.read()
        if not ok:
            return
        if index in wanted:
            yield frame
        index += 1


def _every_nth_frame(capture):
    index = 0
    used = 0
    while used < MAX_FRAMES:
        ok, frame = capture.read()
        if not ok:
            return
        if index % FRAME_STEP == 0:
            used += 1
            yield frame
        index += 1


def _to_thumbnail(frame) -> bytes | None:
    if frame is None:
        return None
    height = int(frame.shape[0] * THUMB_WIDTH / frame.shape[1])
    resized = cv2.resize(frame, (THUMB_WIDTH, height))
    ok, buffer = cv2.imencode(".jpg", resized, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return buffer.tobytes() if ok else None
