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

## Ergänzung Kamera-Dashboard (2026-08-27)

| Erwarteter Bezeichner | Existiert | Tatsächlicher Name / Signatur | Fundstelle |
| --- | --- | --- | --- |
| `cam.arm` | ja | `@property arm` → `bool` (`self.motion_enabled`) | `camera.py:130` |
| `cam.async_arm(value)` | ja | `async async_arm(value)`; `value` wahrheitswertig → `request_motion_detection_enable`/`_disable(camera_type=self.camera_type)` | `camera.py:134` |
| `cam.battery` | ja | `@property battery` → `str` (`self.battery_state`, z. B. `"ok"`), befüllt aus `config["battery_state"]`/`config["battery"]` in `update()` | `camera.py:83`, `camera.py:307` |
| `cam.snap_picture()` | ja (Semantik abweichend) | `async snap_picture()` → gibt die Antwort von `request_new_image` zurück, **nicht** das Bild; lädt intern per `get_media()` von der **noch alten** `self.thumbnail`-URL und cached dieses Bild in `self._cached_image` (siehe Konsequenzen unten) | `camera.py:267` |
| `cam.image_from_cache` | ja | `@property image_from_cache` → `bytes` oder `None` (`self._cached_image`) | `camera.py:101` |
| `sync.arm` / `sync.async_arm(value)` | ja | `@property arm` → `bool`/`None` (`network_info["network"]["armed"]`, setzt bei Fehler zusätzlich `self.available = False`); `async async_arm(value)` → `request_system_arm`/`_disarm` | `sync_module.py:106`, `sync_module.py:124` |
| `sync.cameras` | ja | `CaseInsensitiveDict` Name → `BlinkCamera`-Instanz (bzw. `BlinkCameraMini`/`BlinkDoorbell`) | `sync_module.py:45` |
| `blink.refresh(force=...)` | ja | `async refresh(force=False, force_cache=False)` → `bool`; ohne `force`/`force_cache` gedrosselt über `check_if_ok_to_update()` gegen `refresh_rate` (Default `DEFAULT_REFRESH = 30` Sekunden) | `blinkpy.py:126`, `helpers/constants.py:39` |

### Konsequenzen für die Folge-Tasks

- **`snap_picture()` liefert nicht das Bild — und sein interner Download holt das ALTE.** Der eingebaute `get_media()`-Aufruf (`camera.py:275`) lädt von `self.thumbnail`, und dieses Feld trägt zu diesem Zeitpunkt noch die *vorherige* URL: blinkpy schreibt die neue erst in `update()` (`camera.py:415`), also erst bei einem späteren Refresh. Die Kamera braucht zudem einige Sekunden für Aufnahme und Upload. Wer nach `await cam.snap_picture()` einfach `cam.image_from_cache` liest, bekommt deshalb **garantiert das Vorbild** — ohne Fehler und ohne dass es jemandem auffällt.

  Richtig ist: `cam.thumbnail` **vor** dem Auslösen merken, dann in einer Schleife `await blink.refresh(force=True)` aufrufen, bis sich `cam.thumbnail` ändert; erst dann ist `cam.image_from_cache` das neue Bild. Ändert sich die URL im Zeitbudget nicht, ist ein ehrlicher Fehler besser als das alte Bild als „Schnappschuss" auszugeben. So umgesetzt in `BlinkClient.snapshot()`.
- **`image_from_cache` ist nicht exklusiv an `snap_picture()` gekoppelt.** Ein normaler `sync.refresh()`/`blink.refresh()`-Zyklus füllt dasselbe Feld über `update_images()` (`camera.py:337`ff., Zeile `426`). Ein UI, das „letztes Bild" anzeigen will, kann also auch ohne expliziten Schnappschuss ein (ggf. älteres) Bild bekommen; wichtig für die Erwartungshaltung, ob ein angezeigtes Bild wirklich frisch ist.
- **`BlinkCameraMini.arm` weicht semantisch von `BlinkCamera.arm` ab.** Bei einer Blink Mini liest `cam.arm` NICHT `motion_enabled` der Kamera selbst, sondern gibt `self.sync.arm` zurück (den Scharf-Status des ganzen Sync-Moduls) — überschrieben in `camera.py:568`. `async_arm(value)` ist dagegen bei der Mini **nicht** überschrieben und schaltet weiterhin individuell per Kamera-`camera_id`. Für ein UI heißt das: das Anzeigen des Scharf-Status einer Mini nach `arm` kann inkonsistent zu dem wirken, was ein individueller `async_arm()`-Aufruf gerade bewirkt hat — ein direkter Soll/Ist-Vergleich über `cam.arm` nach `cam.async_arm(...)` ist für Minis nicht zuverlässig, ein Refresh des Sync-Moduls ist vorzuziehen.
- **Es gibt keine `BlinkWiredFloodlight`-Klasse.** Der Plan ging von einer eigenen Unterklasse aus; tatsächlich ist Flutlicht ein Verhalten der Basisklasse `BlinkCamera` selbst, gesteuert über `async_set_floodlight(enable)` und gültig, wenn `product_type == "superior"` ist (nur eine Warnung im Log, kein harter Fehler, wenn der Typ abweicht) — `camera.py:197`. Nur `BlinkCameraMini` (`camera_type = "mini"`) und `BlinkDoorbell` (`camera_type = "doorbell"`) existieren als tatsächliche Unterklassen von `BlinkCamera`; daneben gibt es keine weitere Kamera-Subklasse in dieser Version.
- **`sync.arm` hat einen Seiteneffekt bei Fehlern.** Fehlt `network_info` (z. B. vor dem ersten erfolgreichen `start()`/`refresh()`), liefert `sync.arm` nicht nur `None`, sondern setzt zusätzlich `self.available = False`. Ein Poller, der `sync.arm` blind zur Statusanzeige abfragt, kann dadurch ungewollt den Verfügbarkeits-Status der Anbindung kippen.
- **`blink.refresh()` ohne `force=True` kann stillschweigend nichts tun** (liefert dann `False`, wenn innerhalb der 30-Sekunden-Drossel aufgerufen). Ein Endpunkt „Jetzt aktualisieren" braucht `force=True`, sonst wirkt ein Klick kurz nach dem letzten automatischen Poll wortlos wie ein No-Op.

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
