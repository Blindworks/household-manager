# Ladesäule frei – Push-Benachrichtigung (Design, kurz)

**Anlass:** Am Wandtablet (`/tablet/charging`) sind die gewünschten Ladesäulen belegt. Man will nicht
auf die Karte starren, sondern eine Push-Nachricht bekommen, sobald ein Ladepunkt frei wird.

## Verhalten (mit dem Nutzer abgestimmt)
- Je Station in der rechten Liste ein Knopf „Benachrichtigen, wenn frei“ – **nur, wenn dort gerade kein
  Ladepunkt frei ist**, nie bei Superchargern (keine Belegungsdaten).
- Antippen öffnet eine Auswahl **der Person** (Nutzerentscheidung B): nur Personen mit mindestens einem
  Push-Gerät, ohne das Wandtablet-Konto. Danach zeigt die Station „🔔 Benachrichtigt: <Name>“, ein Tipp
  darauf hebt das Warten auf.
- Sobald mindestens ein Ladepunkt frei ist: **genau eine** Push-Nachricht an diese Person
  („<Station>: 1 von 2 Ladepunkten frei“), danach endet das Warten.
- Favoriten werden jede Minute geprüft, andere Stationen über den 5-Minuten-Umkreis-Poll.
- Nach **4 Stunden** ohne freien Punkt verfällt das Warten still.
- Aufträge liegen **nur im Speicher**; ein Backend-Neustart (Deploy) verwirft sie (akzeptiert).

## Ansätze
1. **Backend-Wächter in den bestehenden Polls (gewählt):** prüft nach jedem Favoriten- und Umkreis-Poll
   den Stand im `ChargingSnapshot`. Kein Zusatzabruf bei EnBW.
2. Automatisch angelegter Flow je Auftrag: verworfen – unnötig umständlich, Nicht-Favoriten haben keine Entität.
3. Prüfung im Tablet-Browser: verworfen – schläft der Bildschirm (Soft-Off), kommt nichts.

## Umsetzung (Entscheidungen des Bauenden)
- `ChargingWatchService` (Paket `charging`): Aufträge je Station und Person (mehrere Personen je Station
  erlaubt), `Clock`-Bean, wirft im Poll nie. Freizahl: Favoriten-Details vor Umkreis-Stand
  (`ChargingSnapshot.freeCount`, einzige Definition). Ohne bekannten Stand bleibt der Auftrag liegen.
- Versand über `PushNotificationService.sendToUser`; scheitert er, ist der Auftrag trotzdem erledigt
  (eine Zustellung, Fehler als Warnung im Log).
- API: `GET /v1/charging/watch-candidates` (Personen mit Push-Gerät), `PUT`/`DELETE
  /v1/charging/watches/{stationId}/{userId}`. Anlegen bei schon freiem Ladepunkt ⇒ 409, Person ohne
  Push-Gerät ⇒ 400, Supercharger ⇒ 400. `PUT`/`DELETE` KIOSK (Muster Favoriten-Stern), Lesen KIOSK über
  die generische GET-Regel. `GET /v1/charging/watches` liefert die laufenden Aufträge (Station, Person, Name);
  `PUT`/`DELETE` antworten mit derselben Liste. Bewusst nicht an `StationResponse` gehängt (eigener Abruf je Refresh).
- Kein Audit (wie der Favoriten-Stern), keine Entität, keine DB-Tabelle. Website `/charging` bleibt unverändert.
