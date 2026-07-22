"""Meldet denselben Schluessel (Person) hoechstens einmal pro Zeitfenster."""
import time


class Cooldown:
    def __init__(self, seconds: float, clock=time.monotonic):
        self._seconds = seconds
        self._clock = clock
        self._last: dict[str, float] = {}

    def allow(self, key: str) -> bool:
        now = self._clock()
        last = self._last.get(key)
        if last is not None and (now - last) < self._seconds:
            return False
        self._last[key] = now
        return True
