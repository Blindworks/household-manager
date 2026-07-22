"""Tests fuer den Analyzer ohne InsightFace-Modell: das Modell wird durch einen
Stub ersetzt, damit die Suite auch ohne Netz und ohne Modell-Download laeuft."""
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from app.analyzer import MAX_FRAMES, THUMB_WIDTH, FaceAnalyzer, _to_thumbnail


def noise_frame(height=480, width=640):
    return np.random.default_rng(7).integers(0, 256, (height, width, 3), dtype=np.uint8)


class StubFaceModel:
    """Liefert pro Aufruf so viele Pseudo-Gesichter wie im Fahrplan hinterlegt."""

    def __init__(self, faces_per_call=()):
        self._faces_per_call = list(faces_per_call)
        self.calls = 0

    def get(self, frame):
        count = self._faces_per_call[self.calls] if self.calls < len(self._faces_per_call) else 0
        self.calls += 1
        return [SimpleNamespace(normed_embedding=np.zeros(3, dtype=np.float32)) for _ in range(count)]


def analyzer_with(stub):
    analyzer = FaceAnalyzer.__new__(FaceAnalyzer)  # umgeht das Laden des Modells
    analyzer._app = stub
    return analyzer


def write_clip(path, frames):
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), 10.0, (64, 48))
    if not writer.isOpened():
        pytest.skip("kein MJPG-Encoder verfuegbar")
    for _ in range(frames):
        writer.write(noise_frame(48, 64))
    writer.release()
    return str(path)


def test_thumbnail_of_missing_frame_is_none():
    assert _to_thumbnail(None) is None


def test_thumbnail_is_decodable_jpeg_scaled_to_thumb_width():
    thumbnail = _to_thumbnail(noise_frame(480, 640))

    decoded = cv2.imdecode(np.frombuffer(thumbnail, np.uint8), cv2.IMREAD_COLOR)
    assert decoded.shape[1] == THUMB_WIDTH
    assert decoded.shape[0] == 240


def test_broken_image_bytes_yield_no_embeddings():
    analyzer = analyzer_with(StubFaceModel())

    assert analyzer.embeddings_from_image(b"kaputt") == []
    assert analyzer._app.calls == 0


def test_unreadable_clip_yields_no_embeddings_and_no_thumbnail(tmp_path):
    analyzer = analyzer_with(StubFaceModel())

    embeddings, thumbnail = analyzer.analyze_clip(str(tmp_path / "gibt-es-nicht.mp4"))

    assert embeddings == []
    assert thumbnail is None


def test_short_clip_analyses_every_fifth_frame(tmp_path):
    clip = write_clip(tmp_path / "kurz.avi", frames=30)
    analyzer = analyzer_with(StubFaceModel())

    analyzer.analyze_clip(clip)

    assert analyzer._app.calls == 6  # Frames 0, 5, 10, 15, 20, 25


def test_long_clip_stops_at_the_frame_limit(tmp_path):
    clip = write_clip(tmp_path / "lang.avi", frames=100)
    analyzer = analyzer_with(StubFaceModel())

    analyzer.analyze_clip(clip)

    assert analyzer._app.calls == MAX_FRAMES


def test_clip_without_faces_yields_no_thumbnail(tmp_path):
    clip = write_clip(tmp_path / "leer.avi", frames=30)
    analyzer = analyzer_with(StubFaceModel())

    embeddings, thumbnail = analyzer.analyze_clip(clip)

    assert embeddings == []
    assert thumbnail is None


def test_thumbnail_comes_from_the_frame_with_most_faces(tmp_path):
    clip = write_clip(tmp_path / "gesichter.avi", frames=30)
    analyzer = analyzer_with(StubFaceModel(faces_per_call=[1, 0, 2, 0, 0, 0]))

    embeddings, thumbnail = analyzer.analyze_clip(clip)

    assert len(embeddings) == 3
    assert thumbnail is not None
