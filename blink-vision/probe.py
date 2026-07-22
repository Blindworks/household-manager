"""Einmaliger Spike: verifiziert blinkpy-Login, Kameraliste und Local-Storage-Clips.

Aufruf:  .venv/Scripts/python probe.py   (fragt Zugangsdaten interaktiv ab)

Geschrieben gegen blinkpy 0.25.9. Abweichungen zu aelteren blinkpy-Versionen
(0.23/0.24) sind in BLINKPY-API.md dokumentiert - insbesondere der 2FA-Ablauf,
der seit dem OAuth-v2-Umbau ueber eine Exception statt ueber ein Flag laeuft.
"""

import asyncio
import getpass
import json
from pathlib import Path

from aiohttp import ClientSession
from blinkpy.auth import Auth, BlinkTwoFARequiredError
from blinkpy.blinkpy import Blink

DATA = Path(__file__).parent / "data"
SESSION_FILE = DATA / "blink-session.json"

# Zugangsdaten werden bewusst NICHT persistiert. blink.save() wuerde sie
# mitschreiben, weil Auth.login_attributes das komplette data-Dict (inkl.
# username/password) zurueckgibt.
SECRET_KEYS = ("username", "password")


async def save_session(blink: Blink) -> None:
    """Speichert nur Token/Session-Attribute, keine Zugangsdaten."""
    attributes = {
        key: value
        for key, value in blink.auth.login_attributes.items()
        if key not in SECRET_KEYS
    }
    SESSION_FILE.write_text(json.dumps(attributes, indent=2), encoding="utf-8")


def build_auth(session: ClientSession) -> Auth:
    """Baut Auth aus gespeicherter Session oder aus interaktiver Eingabe."""
    if SESSION_FILE.exists():
        login_data = json.loads(SESSION_FILE.read_text(encoding="utf-8"))
    else:
        login_data = {
            "username": input("Blink/Amazon E-Mail: "),
            "password": getpass.getpass("Passwort: "),
        }
    return Auth(login_data, no_prompt=True, session=session)


async def login(blink: Blink) -> bool:
    """Fuehrt den Login inklusive 2FA-Nachfrage durch."""
    try:
        return await blink.start()
    except BlinkTwoFARequiredError:
        code = input("2FA-PIN (per SMS/E-Mail): ")
        # send_2fa_code() schliesst den OAuth-Flow ab UND ruft intern
        # setup_post_verify() auf - ein zusaetzlicher Aufruf ist unnoetig.
        return await blink.send_2fa_code(code)


def print_overview(blink: Blink) -> None:
    """Gibt Sync-Module und Kameras aus."""
    print("== Sync-Module ==")
    for name, sync in blink.sync.items():
        print(f"  {name}: {type(sync).__name__}")
        print(f"    local_storage={sync.local_storage} serial={sync.serial}")
    print("== Kameras ==")
    for name, camera in blink.cameras.items():
        print(f"  {name}: id={camera.camera_id} type={camera.camera_type}")


async def probe_local_storage(blink: Blink) -> None:
    """Liest das Local-Storage-Manifest und laedt den neuesten Clip herunter."""
    for name, sync in blink.sync.items():
        if not sync.local_storage:
            print(f"== {name}: kein Local Storage aktiv ==")
            continue
        await sync.refresh()
        # Kein oeffentlicher Zugriff auf das Manifest - _local_storage["manifest"]
        # ist ein SortedSet, aufsteigend nach created_at sortiert.
        manifest = sync._local_storage["manifest"]
        print(f"== Manifest {name}: {len(manifest)} Clips ==")
        for item in reversed(list(manifest)[-5:]):
            print(f"  id={item.id} camera={item.name} created={item.created_at}")
        if not manifest:
            continue
        newest = manifest[-1]
        await newest.prepare_download(blink)
        target = DATA / "probe-clip.mp4"
        if await newest.download_video(blink, str(target)):
            print("Clip gespeichert:", target)
        else:
            print("Download des neuesten Clips fehlgeschlagen.")


async def main() -> None:
    DATA.mkdir(exist_ok=True)
    async with ClientSession() as session:
        blink = Blink(session=session)
        blink.auth = build_auth(session)
        if not await login(blink):
            print("Login fehlgeschlagen - siehe Log-Ausgabe oben.")
            return
        await save_session(blink)

        print_overview(blink)
        await probe_local_storage(blink)


if __name__ == "__main__":
    asyncio.run(main())
