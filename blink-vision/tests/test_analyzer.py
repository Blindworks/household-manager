"""Tests fuer den Analyzer ohne InsightFace-Modell: das Modell wird durch einen
Stub ersetzt, damit die Suite auch ohne Netz und ohne Modell-Download laeuft."""
import threading
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from app.analyzer import (
    FRAME_STEP,
    MAX_FRAMES,
    THUMB_WIDTH,
    FaceAnalyzer,
    _selected_frames,
    _to_thumbnail,
    target_frame_indices,
)


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


class FakeCapture:
    """cv2.VideoCapture-Ersatz mit steuerbarer - auch unbrauchbarer - Frameanzahl.
    Die 'Frames' sind schlichte Indizes; _selected_frames reicht sie nur durch."""

    def __init__(self, frames: int, reported_count: int):
        self._frames = frames
        self._reported_count = reported_count
        self._position = 0

    def get(self, _property):
        return self._reported_count

    def read(self):
        if self._position >= self._frames:
            return False, None
        frame = self._position
        self._position += 1
        return True, frame


def analyzer_with(stub):
    analyzer = FaceAnalyzer.__new__(FaceAnalyzer)  # umgeht das Laden des Modells
    analyzer._app = stub
    analyzer._lock = threading.Lock()
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


def test_short_clip_keeps_the_minimum_frame_distance(tmp_path):
    clip = write_clip(tmp_path / "kurz.avi", frames=30)
    analyzer = analyzer_with(StubFaceModel())

    analyzer.analyze_clip(clip)

    assert analyzer._app.calls == 6  # 30 Frames / Mindestabstand 5


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


def test_target_indices_span_the_whole_clip():
    indices = target_frame_indices(900)

    assert len(indices) == MAX_FRAMES
    assert indices[0] == 0
    assert indices[-1] == 899   # auch das Clipende wird analysiert


def test_target_indices_keep_the_minimum_distance_on_short_clips():
    indices = target_frame_indices(30)

    assert len(indices) == 6
    assert min(b - a for a, b in zip(indices, indices[1:])) >= FRAME_STEP


def test_target_indices_of_empty_or_unknown_length():
    assert target_frame_indices(0) == []
    assert target_frame_indices(-1) == []
    assert target_frame_indices(1) == [0]


def test_frames_are_read_at_the_computed_indices():
    capture = FakeCapture(frames=900, reported_count=900)

    assert list(_selected_frames(capture)) == target_frame_indices(900)


def test_unknown_frame_count_falls_back_to_every_nth_frame():
    capture = FakeCapture(frames=900, reported_count=0)

    assert list(_selected_frames(capture)) == [i * FRAME_STEP for i in range(MAX_FRAMES)]


def test_overstated_frame_count_ends_cleanly_at_the_last_frame():
    capture = FakeCapture(frames=40, reported_count=900)

    frames = list(_selected_frames(capture))

    assert frames == [i for i in target_frame_indices(900) if i < 40]
