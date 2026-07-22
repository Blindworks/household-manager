# blinkpy-API-Verifikation (Spike, Task 1)

Installierte Version: **blinkpy 0.25.9** (`pip show blinkpy`), Python 3.13.1, aiohttp 3.14.2.

Die im Plan vorgesehenen Bezeichner wurden **statisch gegen die installierte
Bibliotheksquelle** unter `.venv/Lib/site-packages/blinkpy/` geprüft (kein
Netzzugriff, keine echten Zugangsdaten). Zeilenangaben beziehen sich auf genau
diese installierte Version.

## Ergebnis-Tabelle

| Erwarteter Bezeichner | Existiert | Tatsächlicher Name / Signatur | Fundstelle |
| --- | --- | --- | --- |
| `Blink(session=...)` | ja | `Blink(refresh_rate=30, motion_interval=1, no_owls=False, session=None)` | `blinkpy.py:43` |
| `blink.start()` | ja | `async start()` → `bool`; wirft `BlinkTwoFARequiredError` weiter | `blinkpy.py:152` |
| `blink.key_required` | **nein** | ersatzlos entfallen – 2FA wird als Exception `BlinkTwoFARequiredError` signalisiert | `blinkpy.py:162`, `auth.py:341`, `auth.py:446` |
| `blink.save(path)` | ja | `async save(file_name)` → schreibt `auth.login_attributes` als JSON | `blinkpy.py:352`, `helpers/util.py:34` |
| `blink.sync` (dict) | ja | `CaseInsensitiveDict` Name → `BlinkSyncModule` | `blinkpy.py:66` |
| `blink.cameras` (dict) | ja | `CaseInsensitiveDict` Name → `BlinkCamera` | `blinkpy.py:70`, gefüllt in `blinkpy.py:192` |
| `Auth(dict, no_prompt=..., session=...)` | ja | `Auth(login_data=None, no_prompt=False, session=None, agent=..., app_build=..., callback=None)` | `auth.py:30` |
| `auth.send_auth_key(blink, key)` | **nein** | ersetzt durch `blink.send_2fa_code(code)` → `bool` (ruft intern `auth.complete_2fa_login(code)`) | `blinkpy.py:98`, `auth.py:383` |
| `blink.setup_post_verify()` | ja | `async setup_post_verify()` → `bool`; **wird von `send_2fa_code()` bereits selbst aufgerufen** | `blinkpy.py:176`, Aufruf in `blinkpy.py:123` |
| `sync.local_storage` | ja | `@property local_storage` → `bool` (`_local_storage["status"]`) | `sync_module.py:114` |
| `sync.refresh()` | ja | `async refresh(force_cache=False)`; ruft intern `update_local_storage_manifest()` | `sync_module.py:277` |
| `sync._local_storage["manifest"]` | ja | privat, Typ `SortedSet` von `LocalStorageMediaItem`, **aufsteigend** nach `created_at`; kein öffentlicher Zugriff vorhanden | `sync_module.py:60`, `:65`, befüllt in `:464` |
| `item.id` | ja | `@property id` → `int` | `sync_module.py:672` |
| `item.name` | ja | `@property name` → Kameraname (`str`) | `sync_module.py:677` |
| `item.created_at` | ja | `@property created_at` → **`datetime.datetime`** (kein String) | `sync_module.py:682`, Konvertierung in `:662` |
| `item.prepare_download(blink)` | ja | `async prepare_download(blink, max_retries=4)` | `sync_module.py:705` |
| `item.download_video(blink, path)` | ja | `async download_video(blink, file_name, max_retries=4)` → **`bool`** | `sync_module.py:730` |
| `cam.camera_id` | ja | `str` (aus `config["id"]`) | `camera.py:28`, gesetzt in `:301` |
| `cam.camera_type` | ja | `str` (`""`, `"mini"`, `"doorbell"`) | `camera.py:48` |

Zusätzlich nützlich und vorhanden: `item.url(manifest_id=None)` (`sync_module.py:692`),
`item.delete_video(blink)` (`:714`), `item.size` (`:687`), `sync.local_storage_manifest_ready`
(`sync_module.py:119`), `sync.serial` (`:37`).

## Vorgenommene Korrekturen an `probe.py`

1. **2FA-Ablauf umgestellt.** Statt `blink.key_required` / `auth.send_auth_key(blink, key)`
   fängt das Skript `BlinkTwoFARequiredError` aus `blink.start()` ab und ruft
   `blink.send_2fa_code(code)`. Der zusätzliche `setup_post_verify()`-Aufruf entfällt,
   weil `send_2fa_code()` ihn selbst ausführt.
2. **Neuester Clip ist `manifest[-1]`, nicht `manifest[0]`.** Das Manifest ist ein
   `SortedSet`, das aufsteigend nach `created_at` sortiert – `manifest[0]` wäre der
   *älteste* Clip. blinkpy selbst iteriert dafür `reversed(manifest)`
   (`sync_module.py:369`).
3. **`blink.save()` wird nicht verwendet.** `Auth.login_attributes` (`auth.py:79`)
   gibt das komplette `data`-Dict zurück, in dem `validate_login()` (`auth.py:106`)
   `username` und `password` ablegt – `blink.save()` würde das Passwort im Klartext
   in die Session-Datei schreiben. `probe.py` filtert diese beiden Schlüssel heraus.
   *Folge:* Läuft der Refresh-Token ab, schlägt der stille Re-Login fehl und die
   Zugangsdaten müssen erneut eingegeben werden. Für den späteren Sidecar sind die
   Zugangsdaten ohnehin aus der Umgebung zu beziehen, nicht aus der Session-Datei.
4. **`local_storage` ist ein Property, kein optionales Attribut.** Das `getattr(...)`
   mit Default aus dem Plan ist unnötig und würde einen echten Fehler verschlucken.
5. **`requirements.txt` auf `blinkpy>=0.25.9,<0.26` gesetzt** (statt `>=0.23`), weil
   0.23/0.24 die alte, inkompatible 2FA-API haben.

## Noch offen: echter End-to-End-Login

Der Login gegen die Blink-Cloud wurde **nicht** ausgeführt – er benötigt die echten
Amazon-/Blink-Zugangsdaten und einen 2FA-PIN. Dieser Schritt ist vom Nutzer
interaktiv nachzuholen:

```powershell
cd blink-vision
.venv\Scripts\python probe.py
```

Zu bestätigen ist dabei:

- Login inkl. 2FA-PIN funktioniert und `data/blink-session.json` entsteht
  (ohne `username`/`password` im Inhalt).
- Die Türkamera taucht in der Kameraliste auf – Name und `camera_id` notieren.
- Das Sync-Modul meldet `local_storage=True` (nur Sync Module 2 mit USB-Stick).
  Ist es `False`, sind keine lokalen Clips verfügbar und die spätere Poller-Strategie
  (Tasks 8/10) muss auf Cloud-Clips ausweichen.
- `data/probe-clip.mp4` wird geschrieben und ist abspielbar – damit steht fest, dass
  der spätere Sidecar an verwertbares Videomaterial kommt.
