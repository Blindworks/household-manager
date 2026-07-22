"""Tests fuer den PersonStore: Validierung und Normalisierung der vom Backend
gelieferten Embeddings. Nicht normalisierte Vektoren wuerden den Cosine-Vergleich
im Matcher verfaelschen, ein Nullvektor wuerde ihn durch Null teilen lassen."""
import numpy as np

from app.persons import PersonStore


def person(person_id: int, name: str, embeddings: list) -> dict:
    return {"personId": person_id, "name": name, "embeddings": embeddings}


def test_empty_payload_leaves_store_empty():
    store = PersonStore()

    store.replace([])

    assert store.all() == []


def test_entry_without_embeddings_key_is_kept_without_embeddings():
    store = PersonStore()

    store.replace([{"personId": 1, "name": "Benedikt"}])

    assert len(store.all()) == 1
    assert store.all()[0].name == "Benedikt"
    assert store.all()[0].embeddings == []


def test_zero_vector_embedding_is_dropped():
    store = PersonStore()

    store.replace([person(1, "Benedikt", [[0, 0, 0]])])

    assert store.all()[0].embeddings == []


def test_zero_vector_is_dropped_but_valid_embeddings_survive():
    store = PersonStore()

    store.replace([person(1, "Benedikt", [[0, 0, 0], [3, 4, 0]])])

    embeddings = store.all()[0].embeddings
    assert len(embeddings) == 1
    assert np.allclose(embeddings[0], [0.6, 0.8, 0.0])


def test_embeddings_are_normalised_to_unit_length():
    store = PersonStore()

    store.replace([person(1, "Benedikt", [[3, 4, 0], [0, 0, 5]])])

    for embedding in store.all()[0].embeddings:
        assert embedding.dtype == np.float32
        assert np.isclose(np.linalg.norm(embedding), 1.0)


def test_person_id_is_converted_to_int():
    store = PersonStore()

    store.replace([{"personId": "7", "name": "Partnerin", "embeddings": []}])

    assert store.all()[0].person_id == 7


def test_replace_discards_previous_content():
    store = PersonStore()
    store.replace([person(1, "Benedikt", [[1, 0, 0]])])

    store.replace([person(2, "Partnerin", [[0, 1, 0]])])

    assert [p.person_id for p in store.all()] == [2]
    assert [p.name for p in store.all()] == ["Partnerin"]


def test_replace_with_empty_payload_clears_previous_content():
    store = PersonStore()
    store.replace([person(1, "Benedikt", [[1, 0, 0]])])

    store.replace([])

    assert store.all() == []
