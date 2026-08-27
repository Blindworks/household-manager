"""Bewegungs-Waechter: neue Local-Storage-Clips -> Motion-Webhook ans Backend.

Zweiter, unabhaengiger Verbraucher des Manifests neben der Gesichtserkennung.
Fasst weder deren Dedupe-Store noch deren Download-Pfad an und laeuft in einem
eigenen, langsameren Takt (MOTION_POLL_SECONDS) — ein Umbau des 10-s-Durchlaufs
der Erkennung waere ein Eingriff in den Pfad, an dem der Auto-Unlock haengt.
"""
import logging

log = logging.getLogger(__name__)


class MotionWatcher:
    """Hochwassermarke je Kamera (createdAt, im Speicher).

    Erste Sichtung einer Kamera setzt nur die Marke, ohne zu feuern — sonst
    ergoesse sich beim Start der komplette Alt-Bestand des Manifests als
    Meldeschwall. Die Marke wird erst NACH erfolgreichem Webhook vorgezogen:
    ist das Backend gerade nicht erreichbar (Deploy), wird dieselbe Bewegung
    im naechsten Zyklus erneut gemeldet statt verloren zu gehen.

    Zeitstempel werden als String verglichen. Das traegt, weil sie alle aus
    derselben Quelle stammen (`datetime.isoformat()` in `manifest_snapshot`):
    der Teil bis zur Sekunde hat feste Breite, und die nur bei Bedarf
    angehaengten Mikrosekunden sortieren korrekt, weil '.' unter allen Ziffern
    liegt. Wer die Quelle je auf ein anderes Format umstellt (gemischt naiv und
    zonenbehaftet etwa), muss hier auf echte Datumsobjekte umstellen.
    """

    def __init__(self, source, sink):
        self._source = source          # hat: async manifest_snapshot() -> list[dict]
        self._sink = sink              # hat: async post_motion(events) -> None
        self._marks: dict[str, str] = {}   # cameraId -> hoechstes bekanntes createdAt (ISO)

    async def check(self) -> None:
        """Ein Durchlauf; wirft nie (der Poll-Loop darf nicht reissen)."""
        try:
            snapshot = await self._source.manifest_snapshot()
        except Exception as ex:
            log.warning("Bewegungs-Check: Manifest nicht lesbar: %s", ex)
            return

        # Juengster Zeitstempel je Kamera. Vorab in einem eigenen Durchgang, weil
        # das Manifest nur JE SYNC-MODUL absteigend sortiert ist — auf eine
        # Reihenfolge darf sich hier nichts verlassen.
        newest: dict[str, str] = {}
        for entry in snapshot:
            camera_id = entry["cameraId"]
            newest[camera_id] = max(entry["createdAt"], newest.get(camera_id, ""))

        known = set(self._marks)

        # Erste Sichtung einer Kamera: nur die Marke setzen, nicht feuern. Das
        # braucht keinen Webhook und wird deshalb sofort uebernommen.
        for camera_id, created_at in newest.items():
            if camera_id not in known:
                self._marks[camera_id] = created_at

        events = [entry for entry in snapshot
                  if entry["cameraId"] in known
                  and entry["createdAt"] > self._marks[entry["cameraId"]]]
        if not events:
            return

        try:
            await self._sink.post_motion(events)
        except Exception as ex:
            log.warning("Motion-Webhook fehlgeschlagen, naechster Zyklus wiederholt: %s", ex)
            return

        # Erst jetzt vorziehen. Auf den juengsten Zeitstempel der Kamera: alles
        # dazwischen steckt in `events` und ist damit gemeldet.
        for camera_id in {entry["cameraId"] for entry in events}:
            self._marks[camera_id] = newest[camera_id]
