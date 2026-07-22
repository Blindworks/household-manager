"""Vergleich von Gesichts-Embeddings gegen registrierte Personen.
Embeddings sind L2-normalisiert (InsightFace normed_embedding) -> Skalarprodukt = Cosine-Aehnlichkeit."""
from dataclasses import dataclass

import numpy as np


@dataclass
class PersonEmbeddings:
    person_id: int
    name: str
    embeddings: list  # list[np.ndarray]


@dataclass
class Match:
    person_id: int | None
    name: str | None
    confidence: float


def best_match(face_embedding: np.ndarray, persons: list[PersonEmbeddings], threshold: float) -> Match:
    best = Match(person_id=None, name=None, confidence=0.0)
    for person in persons:
        for ref in person.embeddings:
            similarity = float(np.dot(face_embedding, ref))
            if similarity > best.confidence:
                best = Match(person.person_id, person.name, similarity)
    if best.person_id is not None and best.confidence < threshold:
        return Match(person_id=None, name=None, confidence=best.confidence)
    return best
