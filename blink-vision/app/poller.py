"""Poll-Loop: neue Clips -> Analyse -> Matching -> Webhook. Plus ClipDedupe (persistiert)."""
import asyncio
import datetime
import json
import logging
import os
import time
from pathlib import Path

from app import backend_client, config
from app.cooldown import Cooldown
from app.matcher import best_match
from app.motion import MotionWatcher

log = logging.getLogger(__name__)


class ClipDedupe:
    """Merkt sich verarbeitete Clip-IDs (Datei im Datenverzeichnis, begrenzte Groesse)."""

    def __init__(self, state_file: str, max_ids: int = 200):
        self._file = Path(state_file)
        self._max_ids = max_ids
        self._ids: list[str] = []
        if self._file.exists():
            try:
                self._ids = json.loads(self._file.read_text(encoding="utf-8"))
            except Exception as ex:
                log.warning("Dedupe-Datei %s unlesbar, starte mit leerem Stand: %s", self._file, ex)
                self._ids = []

    def is_new(self, clip_id: str) -> bool:
        return clip_id not in self._ids

    def mark_processed(self, clip_id: str) -> None:
        self._ids.append(clip_id)
        self._ids = self._ids[-self._max_ids:]
        self._file.parent.mkdir(parents=True, exist_ok=True)
        self._file.write_text(json.dumps(self._ids), encoding="utf-8")


class Poller:
    def __init__(self, blink_client, analyzer, person_store):
        self._blink = blink_client
        self._analyzer = analyzer
        self._persons = person_store
        Path(config.DATA_DIR).mkdir(parents=True, exist_ok=True)
        self._dedupe = ClipDedupe(os.path.join(config.DATA_DIR, "processed-clips.json"))
        self._cooldown = Cooldown(config.COOLDOWN_SECONDS)
        # sink ist das backend_client-Modul selbst (hat post_motion) — im Test ersetzbar.
        self._motion = MotionWatcher(blink_client, backend_client)
        self.last_poll_at: str | None = None

    async def run_forever(self):
        heartbeat_due = 0.0
        motion_due = 0.0
        while True:
            # Heartbeat und Clip-Abholung sind getrennt abgesichert: ein kurzzeitig
            # nicht erreichbares Backend darf das Blink-Polling nicht aussetzen.
            if time.monotonic() >= heartbeat_due:
                try:
                    await backend_client.post_heartbeat()
                    heartbeat_due = time.monotonic() + config.HEARTBEAT_SECONDS
                except Exception as ex:
                    log.warning("Heartbeat fehlgeschlagen: %s", ex)
            try:
                if self._blink.logged_in:
                    await self._poll_once()
            except Exception as ex:
                log.warning("Poll-Durchlauf fehlgeschlagen: %s", ex)
            # Bewegungs-Check in eigenem, langsamerem Takt und eigener Absicherung:
            # ein Fehler hier darf die Gesichtserkennung nicht aussetzen (und umgekehrt).
            #
            # Die Reihenfolge ist NICHT beliebig: der Check kostet einen
            # Cloud-Roundtrip und im Fundfall einen POST mit 30-s-Timeout. Stuende
            # er vorn, verzoegerte er jeden dritten Erkennungszyklus um diese Zeit —
            # und an der Erkennung haengt der Auto-Unlock, wo Latenz das Einzige
            # ist, was zaehlt. Hinten verzoegert er stattdessen die Bewegungsmeldung,
            # die ohnehin mit 15-60 s veranschlagt ist. Nicht zurueck sortieren.
            # (Bewusst sequenziell statt nebenlaeufig: beide Pfade greifen ueber
            # dieselben blinkpy-Objekte auf sync.refresh() zu.)
            if self._blink.logged_in and time.monotonic() >= motion_due:
                await self._motion.check()   # wirft nie (eigene Absicherung im Watcher)
                motion_due = time.monotonic() + config.MOTION_POLL_SECONDS
            await asyncio.sleep(config.POLL_SECONDS)

    async def _poll_once(self):
        clips = await self._blink.fetch_new_clips(self._dedupe.is_new, config.DATA_DIR)
        self.last_poll_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
        for clip_id, path in clips:
            try:
                await self._process_clip(path)
            except Exception as ex:
                log.warning("Clip %s uebersprungen: %s", clip_id, ex)
            finally:
                # Bewusst auch nach einem Fehler: eine Tuererkennung ist zeitkritisch,
                # eine Minuten spaeter nachgereichte Meldung wuerde ein Schloss oeffnen,
                # obwohl niemand mehr vor der Tuer steht. Ein dauerhaft kaputter Clip
                # wuerde ausserdem endlos neu geladen und analysiert.
                self._dedupe.mark_processed(clip_id)
                Path(path).unlink(missing_ok=True)

    async def _process_clip(self, path: str):
        embeddings, thumbnail = await asyncio.to_thread(self._analyzer.analyze_clip, path)
        if not embeddings:
            return
        matched: dict[int, dict] = {}
        unknown = 0
        for embedding in embeddings:
            match = best_match(embedding, self._persons.all(), config.CONFIDENCE_THRESHOLD)
            if match.person_id is None:
                unknown += 1
            else:
                existing = matched.get(match.person_id)
                if existing is None or match.confidence > existing["confidence"]:
                    matched[match.person_id] = {
                        "personId": match.person_id,
                        "name": match.name,
                        "confidence": round(match.confidence, 4),
                    }
        persons = [p for p in matched.values() if self._cooldown.allow(str(p["personId"]))]
        if not persons and unknown == 0:
            return
        await backend_client.post_recognition(persons, unknown, thumbnail)
