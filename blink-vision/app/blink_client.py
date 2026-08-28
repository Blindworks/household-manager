"""Duenner Wrapper um blinkpy: Login (2FA), Kamera-Auswahl, neue Local-Storage-Clips.
Alle blinkpy-Spezifika leben HIER - verifiziert gegen blinkpy 0.25.9 (siehe BLINKPY-API.md)."""
import asyncio
import json
import logging
from pathlib import Path

from aiohttp import ClientSession
from blinkpy.auth import Auth, BlinkTwoFARequiredError
from blinkpy.blinkpy import Blink

log = logging.getLogger(__name__)

# blink.save() wuerde das Klartext-Passwort mitschreiben (Auth.login_attributes
# liefert das komplette data-Dict inkl. username/password). Wir schreiben die
# Session deshalb selbst und filtern beide Schluessel heraus.
SECRET_KEYS = ("username", "password")

# camera_type der Blink-Tuerklingel; Innenraumkameras melden hier einen leeren Wert.
DOORBELL_TYPE = "doorbell"

# Ein Schnappschuss braucht real einige Sekunden (Aufnahme + Upload zur Cloud).
# 12 x 2 s = 24 s Zeitbudget; der Backend-Client wartet bis zu 60 s.
SNAPSHOT_POLL_SECONDS = 2
SNAPSHOT_MAX_POLLS = 12


class BlinkLoginError(RuntimeError):
    """Login/2FA von der Blink-Cloud abgelehnt."""


class BlinkNotLoggedInError(RuntimeError):
    """Aktion verlangt eine aktive Blink-Anmeldung."""


def _camera_summary(name: str, cam, sync_name: str, sync_armed: bool) -> dict:
    """Reines Mapping BlinkCamera -> API-Dict (testbar ohne Cloud).

    ACHTUNG Blink Mini: BlinkCameraMini.arm ist ueberschrieben und liefert
    sync.arm statt der eigenen motion_enabled (camera.py:560ff), waehrend
    async_arm() sehr wohl die einzelne Kamera schaltet. Bei einer Mini zeigt
    'armed' also den Systemzustand, und ein Einzelschalt-Befehl schlaegt sich
    dort NICHT in der Anzeige nieder. Nicht wegoptimieren - das ist blinkpy-
    Verhalten, keine Nachlaessigkeit hier.
    """
    battery = getattr(cam, "battery", None)
    return {
        "cameraId": str(cam.camera_id),
        "name": name,
        "type": str(getattr(cam, "camera_type", "") or ""),
        "armed": bool(cam.arm),
        "battery": str(battery) if battery is not None else None,
        "syncName": sync_name,
        "syncArmed": bool(sync_armed),
    }


def _clip_summary(item) -> dict:
    return {
        "clipId": str(item.id),
        "createdAt": item.created_at.isoformat(),
        "sizeBytes": getattr(item, "size", None),
    }


async def _manifest_newest_first(sync) -> list:
    """Frisches Local-Storage-Manifest EINES Sync-Moduls, neueste zuerst.

    Das Manifest ist ein SortedSet, AUFSTEIGEND nach created_at - manifest[0]
    ist der AELTESTE Clip. Fuer eine Tueroeffnung zaehlt der neueste, also
    rueckwaerts iterieren (wie blinkpy selbst). Ohne Local Storage (kein Sync
    Module 2 bzw. kein USB-Stick) gibt es nichts zu holen.
    """
    if not sync.local_storage:
        return []
    await sync.refresh()
    return list(reversed(sync._local_storage.get("manifest") or []))


def _find_in_syncs(syncs, camera_id: str):
    """Sucht eine Kamera ueber die stabile camera_id (Namen sind umbenennbar).
    Liefert (name, camera, sync) oder None."""
    for sync in syncs.values():
        for name, cam in sync.cameras.items():
            if str(cam.camera_id) == camera_id:
                return name, cam, sync
    return None


class BlinkClient:
    def __init__(self, data_dir: str, camera_name: str):
        self._creds = Path(data_dir) / "blink-session.json"
        self._camera_name = camera_name
        self._session: ClientSession | None = None
        self._blink: Blink | None = None
        self._pending_2fa = False

    @property
    def logged_in(self) -> bool:
        return self._blink is not None and not self._pending_2fa

    @property
    def pending_2fa(self) -> bool:
        return self._pending_2fa

    async def try_restore_session(self) -> bool:
        """Beim Start: gespeicherte Session laden, falls vorhanden."""
        if not self._creds.exists():
            return False
        try:
            self._session = ClientSession()
            self._blink = Blink(session=self._session)
            self._blink.auth = Auth(json.loads(self._creds.read_text(encoding="utf-8")),
                                    no_prompt=True, session=self._session)
            if not await self._blink.start():
                log.warning("Gespeicherte Blink-Session ist nicht mehr gueltig")
                await self.close()
                return False
            self._pending_2fa = False
            return True
        except BlinkTwoFARequiredError:
            log.warning("Gespeicherte Blink-Session verlangt erneute 2FA-Anmeldung")
            await self.close()
            return False
        except Exception as ex:
            log.warning("Blink-Session-Restore fehlgeschlagen: %s", ex)
            await self.close()
            return False

    async def login(self, username: str, password: str) -> None:
        """Start des Logins. blinkpy signalisiert 2FA per BlinkTwoFARequiredError
        (es gibt KEIN blink.key_required); der Client bleibt dann in pending_2fa."""
        await self.close()
        self._session = ClientSession()
        self._blink = Blink(session=self._session)
        self._blink.auth = Auth({"username": username, "password": password},
                                no_prompt=True, session=self._session)
        try:
            started = await self._blink.start()
        except BlinkTwoFARequiredError:
            self._pending_2fa = True
            return
        if not started:
            # start() faengt LoginError/BlinkSetupError intern ab und gibt nur False
            # zurueck - ohne diese Pruefung saehe ein falsches Passwort wie ein
            # erfolgreicher Login aus und wir wuerden eine unbrauchbare Session speichern.
            await self.close()
            raise BlinkLoginError("Blink hat den Login abgelehnt (Zugangsdaten pruefen).")
        self._pending_2fa = False
        self._save_session()

    async def verify(self, code: str) -> None:
        """Schliesst den Login ab. send_2fa_code ruft intern complete_2fa_login
        UND setup_post_verify - ein separater setup_post_verify-Aufruf waere doppelt."""
        if self._blink is None:
            raise BlinkLoginError("Kein Login-Vorgang aktiv.")
        if not await self._blink.send_2fa_code(code):
            # send_2fa_code meldet eine falsche PIN als False, nicht als Exception.
            raise BlinkLoginError("Blink hat die 2FA-PIN abgelehnt.")
        self._pending_2fa = False
        self._save_session()

    def camera_name(self) -> str | None:
        """Waehlt die Tuerkamera aus.

        Reihenfolge: konfigurierter Name, sonst eine Kamera vom Typ 'doorbell',
        sonst - nur wenn es genau eine gibt - diese eine.

        Bei mehreren Kameras ohne Tuerklingel wird bewusst KEINE geraten: einfach
        die erste zu nehmen hiesse, womoeglich eine Innenraumkamera per
        Gesichtserkennung auszuwerten und die Haustuer an deren Bilder zu haengen.
        """
        if self._blink is None or not self._blink.cameras:
            return None

        if self._camera_name:
            match = self._match_configured_name()
            if match is not None:
                return match
            log.warning("Konfigurierte Kamera %r existiert nicht. Vorhanden: %s",
                        self._camera_name, sorted(self._blink.cameras))

        doorbells = [name for name, cam in self._blink.cameras.items()
                     if str(getattr(cam, "camera_type", "")).lower() == DOORBELL_TYPE]
        if len(doorbells) == 1:
            return doorbells[0]
        if len(doorbells) > 1:
            log.warning("Mehrere Tuerklingel-Kameras gefunden (%s). Bitte BLINK_CAMERA_NAME setzen.",
                        sorted(doorbells))
            return None

        if len(self._blink.cameras) == 1:
            return next(iter(self._blink.cameras))

        log.warning("Mehrere Kameras (%s), aber keine vom Typ '%s'. Ohne BLINK_CAMERA_NAME "
                    "wird keine ausgewertet - bitte die Tuerkamera explizit konfigurieren.",
                    sorted(self._blink.cameras), DOORBELL_TYPE)
        return None

    def _match_configured_name(self) -> str | None:
        """Exakter Treffer, sonst ein Treffer ohne Ruecksicht auf Rand-Leerzeichen.
        Blink-Kameranamen enthalten in der Praxis gern ein angehaengtes Leerzeichen."""
        if self._camera_name in self._blink.cameras:
            return self._camera_name
        wanted = self._camera_name.strip()
        for name in self._blink.cameras:
            if name.strip() == wanted:
                return name
        return None

    async def fetch_new_clips(self, is_new, download_dir: str) -> list[tuple[str, str]]:
        """Liefert [(clip_id, lokaler_pfad)] fuer alle neuen Clips der Zielkamera,
        neueste zuerst. is_new: Callable[[str], bool] - Dedupe-Check des Aufrufers."""
        results: list[tuple[str, str]] = []
        if self._blink is None:
            return results
        camera = self.camera_name()
        if camera is None:
            # Lieber gar nichts auswerten als die falsche Kamera: ohne eindeutige
            # Tuerkamera duerfen keine Clips (z. B. einer Innenraumkamera) durch
            # die Gesichtserkennung laufen und die Haustuer ausloesen.
            log.warning("Keine eindeutige Tuerkamera bestimmbar - es werden keine Clips ausgewertet.")
            return results
        # Bewusst ueber ALLE Sync-Module: die Tuerkamera wird hier ueber ihren
        # Namen bestimmt (camera_name()), nicht ueber eine camera_id.
        for sync in self._blink.sync.values():
            for item in await _manifest_newest_first(sync):
                clip_id = str(item.id)
                if item.name != camera:
                    continue
                if not is_new(clip_id):
                    continue
                path = str(Path(download_dir) / f"clip-{clip_id}.mp4")
                await item.prepare_download(self._blink)
                if not await item.download_video(self._blink, path):
                    log.warning("Clip %s konnte nicht heruntergeladen werden", clip_id)
                    continue
                results.append((clip_id, path))
        return results

    # ==================== Kamera-Dashboard ====================

    def _require_login(self):
        if not self.logged_in:
            raise BlinkNotLoggedInError("Nicht bei Blink angemeldet.")
        return self._blink

    def _require_camera(self, camera_id: str):
        """Loest die camera_id auf oder wirft - der immer gleiche Einstieg der
        Dashboard-Methoden. Liefert AUCH das Sync-Modul der Kamera: alles
        Weitere muss gegen genau dieses eine laufen, sonst waere die Aufloesung
        ueber die stabile camera_id gleich wieder ausgehebelt (zwei Standorte in
        einem Konto duerfen gleichnamige Kameras haben)."""
        blink = self._require_login()
        found = _find_in_syncs(blink.sync, camera_id)
        if found is None:
            raise KeyError(f"Kamera {camera_id} nicht gefunden")
        name, cam, sync = found
        return blink, name, cam, sync

    async def list_cameras(self, force: bool = False) -> list[dict]:
        """Alle Kameras aller Sync-Module (auch Minis/Owls - BlinkOwl erbt von
        BlinkSyncModule und taucht in blink.sync auf).

        force=True ist nach einem Schaltbefehl PFLICHT und keine Optimierung:
        async_arm() setzt cam.arm nicht lokal (camera.py:134), der Wert kommt
        erst mit dem naechsten Refresh - und ein refresh() ohne force ist ueber
        refresh_rate (30 s) gedrosselt und tut dann schlicht nichts. Ohne force
        antwortete die Liste direkt nach dem Schalten mit dem ALTEN Zustand, und
        die Anzeige wuerde sich auf einen Klick hin scheinbar nicht ruehren.
        """
        blink = self._require_login()
        await blink.refresh(force=force)
        result: list[dict] = []
        for sync_name, sync in blink.sync.items():
            for cam_name, cam in sync.cameras.items():
                result.append(_camera_summary(cam_name, cam, sync_name, bool(sync.arm)))
        return result

    async def set_camera_armed(self, camera_id: str, armed: bool) -> None:
        _, _, cam, _ = self._require_camera(camera_id)
        await cam.async_arm(armed)

    async def set_sync_armed(self, sync_name: str, armed: bool) -> None:
        blink = self._require_login()
        if sync_name not in blink.sync:  # CaseInsensitiveDict
            raise KeyError(f"Sync-Modul {sync_name} nicht gefunden")
        await blink.sync[sync_name].async_arm(armed)

    async def snapshot(self, camera_id: str) -> bytes:
        """Loest ein neues Standbild aus und wartet, bis es wirklich da ist.

        snap_picture() weist die Kamera nur an, ein Bild zu machen; das Hochladen
        dauert Sekunden. blinkpy laedt das Bild neu, sobald sich cam.thumbnail
        (die Bild-URL) aendert (camera.py:415) - genau daran erkennen wir ein
        FRISCHES Bild. Ohne diese Warteschleife lieferte der Schnappschuss-Knopf
        stillschweigend das alte Bild zurueck, und niemand saehe den Unterschied.
        """
        blink, _, cam, _ = self._require_camera(camera_id)
        previous_url = cam.thumbnail
        await cam.snap_picture()
        for _ in range(SNAPSHOT_MAX_POLLS):
            await asyncio.sleep(SNAPSHOT_POLL_SECONDS)
            await blink.refresh(force=True)
            if cam.thumbnail != previous_url and cam.image_from_cache:
                return cam.image_from_cache
        # Zeitbudget aufgebraucht: lieber ein ehrlicher Fehler als das alte Bild
        # als "neuer Schnappschuss" auszugeben.
        raise TimeoutError("Blink hat kein neues Standbild geliefert")

    async def thumbnail(self, camera_id: str) -> bytes:
        blink, _, cam, _ = self._require_camera(camera_id)
        image = cam.image_from_cache
        if not image:
            await blink.refresh(force=True)
            image = cam.image_from_cache
        if not image:
            raise RuntimeError("Kein Standbild verfuegbar")
        return image

    async def list_clips(self, camera_id: str) -> list[dict]:
        """Clips der Kamera aus dem Local-Storage-Manifest, neueste zuerst.
        WICHTIG: liest nur - der Dedupe-Store des Erkennungs-Pollers bleibt unberuehrt."""
        _, cam_name, _, sync = self._require_camera(camera_id)
        return [_clip_summary(item) for item in await _manifest_newest_first(sync)
                if item.name == cam_name]

    async def fetch_clip(self, camera_id: str, clip_id: str, cache_dir: str) -> str:
        """Laedt einen Clip in den Cache (einmal pro clip_id) und liefert den Pfad.

        Der Cache waechst unbegrenzt - es gibt bewusst KEIN Aufraeumen in dieser
        Ausbaustufe (wie bei tractive_position und zigbee_measurement). Anders als
        dort sind das echte Videodateien: wird das Dashboard viel genutzt, ist das
        hier die erste Stelle zum Nachziehen.
        """
        blink, cam_name, _, sync = self._require_camera(camera_id)
        target = Path(cache_dir) / f"clip-{clip_id}.mp4"
        if target.exists():
            return str(target)
        for item in await _manifest_newest_first(sync):
            if str(item.id) == clip_id and item.name == cam_name:
                target.parent.mkdir(parents=True, exist_ok=True)
                await item.prepare_download(blink)
                if not await item.download_video(blink, str(target)):
                    raise RuntimeError(f"Clip {clip_id} konnte nicht geladen werden")
                return str(target)
        raise KeyError(f"Clip {clip_id} nicht gefunden")

    async def manifest_snapshot(self) -> list[dict]:
        """Manifest-Metadaten ALLER Kameras, neueste zuerst - Datenquelle des
        Bewegungs-Waechters.

        Liest ausschliesslich Metadaten: kein prepare_download/download_video und
        kein Kontakt zum Dedupe-Store der Gesichtserkennung. Beides waere ein
        Eingriff in den Pfad, an dem der Auto-Unlock haengt - ein hier
        heruntergeladener Clip koennte dort als "schon gesehen" fehlen.

        Die Kamera wird ueber ihre stabile camera_id ausgewiesen; das Manifest
        kennt nur Namen. Ein Clip einer Kamera, die im Sync-Modul nicht (mehr)
        existiert, wird verworfen statt geraten - eine falsche Zuordnung wuerde
        eine Bewegung der falschen Kamera melden.

        Die Reihenfolge ist NUR je Sync-Modul absteigend, modulweit nicht global.
        Der Waechter fuehrt Hochwassermarken je Kamera, fuer ihn ist das
        gleichgueltig; wer hier je eine globale Ordnung braucht, muss sortieren.
        """
        blink = self._require_login()
        snapshot: list[dict] = []
        for sync in blink.sync.values():
            names_to_ids = {name: str(cam.camera_id) for name, cam in sync.cameras.items()}
            for item in await _manifest_newest_first(sync):
                camera_id = names_to_ids.get(item.name)
                if camera_id is None:
                    continue
                snapshot.append({
                    "cameraId": camera_id,
                    "cameraName": item.name,
                    "clipId": str(item.id),
                    "createdAt": item.created_at.isoformat(),
                })
        return snapshot

    def _save_session(self) -> None:
        """Persistiert die Session OHNE Zugangsdaten (siehe SECRET_KEYS)."""
        if self._blink is None:
            return
        attributes = self._blink.auth.login_attributes
        safe = {k: v for k, v in attributes.items() if k not in SECRET_KEYS}
        self._creds.parent.mkdir(parents=True, exist_ok=True)
        self._creds.write_text(json.dumps(safe), encoding="utf-8")

    async def close(self) -> None:
        self._blink = None
        self._pending_2fa = False
        if self._session is not None:
            await self._session.close()
            self._session = None
