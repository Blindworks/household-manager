"""InsightFace-Wrapper: Embeddings aus Bildern/Videoclips (CPU, Modell buffalo_s)."""
import logging

import cv2
import numpy as np
from insightface.app import FaceAnalysis

log = logging.getLogger(__name__)

FRAME_STEP = 5       # jeder 5. Frame
MAX_FRAMES = 12      # Obergrenze pro Clip
THUMB_WIDTH = 320


class FaceAnalyzer:
    def __init__(self):
        self._app = FaceAnalysis(name="buffalo_s", providers=["CPUExecutionProvider"])
        self._app.prepare(ctx_id=-1, det_size=(640, 640))

    def embeddings_from_image(self, image_bytes: bytes) -> list[np.ndarray]:
        """Alle Gesichts-Embeddings eines Einzelbilds (fuer Referenzfotos)."""
        img = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            return []
        return [f.normed_embedding for f in self._app.get(img)]

    def analyze_clip(self, clip_path: str) -> tuple[list[np.ndarray], bytes | None]:
        """Alle Gesichts-Embeddings ueber ausgewaehlte Frames eines Clips
        plus JPEG-Thumbnail des Frames mit den meisten Gesichtern."""
        embeddings: list[np.ndarray] = []
        best_frame = None
        best_face_count = 0
        capture = cv2.VideoCapture(clip_path)
        try:
            index = 0
            used = 0
            while used < MAX_FRAMES:
                ok, frame = capture.read()
                if not ok:
                    break
                if index % FRAME_STEP == 0:
                    used += 1
                    faces = self._app.get(frame)
                    embeddings.extend(f.normed_embedding for f in faces)
                    if len(faces) > best_face_count:
                        best_face_count = len(faces)
                        best_frame = frame
                index += 1
        finally:
            capture.release()
        return embeddings, _to_thumbnail(best_frame)


def _to_thumbnail(frame) -> bytes | None:
    if frame is None:
        return None
    height = int(frame.shape[0] * THUMB_WIDTH / frame.shape[1])
    resized = cv2.resize(frame, (THUMB_WIDTH, height))
    ok, buffer = cv2.imencode(".jpg", resized, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return buffer.tobytes() if ok else None
