"""In-Memory-Store der Personen-Embeddings. Fuehrend ist das Backend:
gefuellt per PUT /persons (Push) oder beim Start via GET /v1/vision/embeddings (Pull)."""
import numpy as np

from app.matcher import PersonEmbeddings


class PersonStore:
    def __init__(self):
        self._persons: list[PersonEmbeddings] = []

    def replace(self, payload: list[dict]) -> None:
        persons = []
        for entry in payload:
            embeddings = [np.array(e, dtype=np.float32) for e in entry.get("embeddings", [])]
            embeddings = [e / np.linalg.norm(e) for e in embeddings if np.linalg.norm(e) > 0]
            persons.append(PersonEmbeddings(
                person_id=int(entry["personId"]), name=entry["name"], embeddings=embeddings))
        self._persons = persons

    def all(self) -> list[PersonEmbeddings]:
        return self._persons
