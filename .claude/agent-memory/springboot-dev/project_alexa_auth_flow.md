---
name: project-alexa-auth-flow
description: Structure and known limitations of AlexaAuthService's inofficial Amazon login/refresh port
metadata:
  type: project
---

`backend/src/main/java/com/household/manager/alexa/AlexaAuthService.java` implements the inofficial
Amazon Alexa PKCE/OAuth login (email+password, MFA, captcha, device registration) and the
refresh-token-to-session flow, ported from `Apollon77/alexa-cookie` (`alexa-cookie.js` +
`lib/proxy.js` on GitHub master as of 2026-07-08).

**Why:** The reference project has two distinct flows that don't map 1:1 to a no-browser Spring
service: (1) `generateAlexaCookie`'s plain email/password POST flow, which never produces a
refresh_token (only session cookies) and relies on a local browser-proxy fallback for anything
requiring device registration; (2) `lib/proxy.js`'s PKCE-based `/ap/signin` authorize URL, which is
the flow that actually yields `openid.oa2.authorization_code` for `/auth/register` and hence a
persistable refresh_token. AlexaAuthService combines pieces of both: it starts at the PKCE authorize
URL (like proxy.js) but drives the GET/POST/parse-hidden-fields loop itself instead of running an
actual browser through an HTTP proxy.

**Known deviations from the reference (documented risk, unverified end-to-end):**
- `AlexaAccount` entity only persists `refreshToken` (+ domain/accountName) — no `frc`/`map-md`/login
  cookies are persisted. So `buildSessionFromRefreshToken()` never restores the original device's
  `frc`/`map-md` cookies across app restarts, unlike `finishCookieRefresh` in the reference, which
  explicitly re-injects `frc`/`map-md` from `formerRegistrationData`. If Amazon starts tying sessions
  to device fingerprint continuity, this could cause a working login to fail on refresh.
- Reference generates TWO unrelated random values (`deviceId` for OAuth `client_id`, `deviceSerial`
  for `registration_data.device_serial`); this port reuses a single `deviceSerial` for both
  (`buildClientId(deviceSerial)` = hex(ascii(deviceSerial)) + hex(ascii("#"+deviceType))). Simplification,
  not expected to break anything since Amazon doesn't appear to correlate the two, but flagged in case
  registration starts failing.
- `registerTokenCapabilities` (PUT `api.amazonalexa.com/v1/devices/@self/capabilities`) from the
  reference is intentionally NOT ported — reference says it's "mainly needed for HTTP/2 push infos",
  not required for behaviors/preview (TTS) calls that `AlexaApiClient` makes.
- MFA/captcha HTML marker detection (`auth-mfa-otpcode`, `name="otpCode"`, `auth-captcha-image`) is
  based on general knowledge of Amazon's signin page, NOT present in the reference file (which just
  errors out on non-.html landing pages) — untested against a live MFA/captcha challenge.

**How to apply:** Before touching this file again, re-fetch the reference
(`https://raw.githubusercontent.com/Apollon77/alexa-cookie/master/alexa-cookie.js` and
`.../lib/proxy.js`) rather than trusting memory of its structure — it may have changed. Cannot be
unit tested (network-dependent); only pure helpers (PKCE challenge, hidden-field regex parsing) are
test-safe. End-to-end verification requires a live Amazon account + physical Echo, which is the
user's manual step, not something to claim as done.
