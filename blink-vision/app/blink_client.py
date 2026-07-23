"""Duenner Wrapper um blinkpy: Login (2FA), Kamera-Auswahl, neue Local-Storage-Clips.
Alle blinkpy-Spezifika leben HIER - verifiziert gegen blinkpy 0.25.9 (siehe BLINKPY-API.md)."""
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


class BlinkLoginError(RuntimeError):
    """Login/2FA von der Blink-Cloud abgelehnt."""


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
        for sync in self._blink.sync.values():
            if not sync.local_storage:
                continue
            await sync.refresh()
            manifest = sync._local_storage.get("manifest") or []
            # Das Manifest ist ein SortedSet, AUFSTEIGEND nach created_at -
            # manifest[0] ist der AELTESTE Clip. Fuer eine Tueroeffnung zaehlt
            # der neueste, also rueckwaerts iterieren (wie blinkpy selbst).
            for item in reversed(manifest):
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
