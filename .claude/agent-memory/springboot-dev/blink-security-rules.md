---
name: blink-security-rules
description: SecurityConfig KIOSK-Whitelist um Blink-Snapshot erweitert (Task 8, feature/blink-kamera-dashboard); Mutationsproben-Methodik bestaetigt
metadata:
  type: project
---

`/v1/blink/cameras/*/snapshot` steht seit Commit fa77c35 in der KIOSK-POST-Whitelist in `SecurityConfig.filterChain` (gleiche Zeile wie Speedtest/Tractive-Refresh/Reboot). Scharf/Unscharf (`/v1/blink/cameras/*/arm|disarm`, `/v1/blink/system/*/arm|disarm`) faellt bewusst auf `anyRequest -> MEMBER` durch — kein eigener Matcher noetig, Muster wie beim Nuki-Schloss.

**Verifiziert per Mutationsprobe (beide Richtungen):**
- Whitelist-Zeile entfernt → genau `kioskDarfBlinkSchnappschussAusloesen` wird rot (403 statt 404), sonst nichts.
- `/v1/blink/cameras/*/disarm` testweise in die Whitelist eingefuegt → `kioskDarfKeineBlinkKameraSchalten` wird korrekt rot (404 statt 403 erwartet). Bestaetigt: der Test ist scharf genug, um eine zu laxe Regel zu erkennen.

`BlinkController` bewusst NICHT im `@WebMvcTest`-Slice von `SecurityRulesTest` — dadurch liefert eine erlaubte Rolle 404 statt 403/200, was fuer den Sicherheitsnachweis ausreicht (Konvention der ganzen Testklasse, siehe Klassenkommentar).

`*` in Spring-Path-Matchern (`AntPathMatcher`/`PathPattern`) trifft genau ein Pfadsegment, keine `/`-uebergreifenden Treffer — bestaetigt durch die Testabdeckung, kein zusaetzlicher Test noetig. Pfad-Traversal (`123/../arm`) ist keine reale Sorge: Tomcat/Spring normalisieren bzw. lehnen `..`-Sequenzen in der Request-URI vor Erreichen der Security-Filterkette ab (StrictHttpFirewall in modernem Spring Security 6.x).

Rollout des Blink-Features insgesamt: siehe [[haushaltskalender]]-Nachbarmuster fuer "Sicherheitsregel + Mutationsprobe"-Vorgehen bei aehnlichen KIOSK/MEMBER-Grenzen.
