import numpy as np

from app.matcher import PersonEmbeddings, best_match


def unit(v):
    a = np.array(v, dtype=np.float32)
    return a / np.linalg.norm(a)


PERSONS = [
    PersonEmbeddings(person_id=1, name="Benedikt", embeddings=[unit([1, 0, 0]), unit([0.9, 0.1, 0])]),
    PersonEmbeddings(person_id=2, name="Partnerin", embeddings=[unit([0, 1, 0])]),
]


def test_matches_closest_person_above_threshold():
    match = best_match(unit([0.95, 0.05, 0]), PERSONS, threshold=0.5)
    assert match.person_id == 1
    assert match.name == "Benedikt"
    assert match.confidence > 0.9


def test_below_threshold_returns_no_person_but_confidence():
    match = best_match(unit([0, 0, 1]), PERSONS, threshold=0.5)
    assert match.person_id is None
    assert match.confidence < 0.5


def test_empty_person_list_returns_unknown():
    match = best_match(unit([1, 0, 0]), [], threshold=0.5)
    assert match.person_id is None
    assert match.confidence == 0.0
