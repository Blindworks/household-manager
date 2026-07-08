# Alexa Sidecar

A small self-contained Node.js service that wraps the
[`alexa-remote2`](https://www.npmjs.com/package/alexa-remote2) library and
exposes a simple HTTP API. The Household-Manager Spring Boot backend calls this
sidecar over HTTP to handle Amazon Alexa login (via alexa-remote2's built-in
browser-login proxy) and Alexa control (list devices, text-to-speech).

## How login works

Amazon login cannot be done with plain username/password reliably, so
alexa-remote2 ships a **browser-login proxy**. The flow is:

1. `POST /login/start` — the sidecar starts the proxy and returns a `proxyUrl`.
2. The user opens that URL in a browser (on the same network) and logs in to
   Amazon normally, including any 2FA.
3. On success, alexa-remote2 emits a `cookie` event. The sidecar persists the
   registration data to `COOKIE_PATH` and is now logged in.
4. On subsequent restarts the sidecar auto-initializes from the stored cookie,
   so no new browser login is needed until it expires.

## Environment variables

| Variable      | Default                     | Description |
|---------------|-----------------------------|-------------|
| `PORT`        | `3456`                      | Port for the HTTP API. |
| `PROXY_HOST`  | `localhost`                 | Host used to build the `proxyUrl` the user opens. Set to a LAN IP or hostname reachable from the user's browser (important in Docker). |
| `PROXY_PORT`  | `3457`                      | Port for the alexa-remote2 browser-login proxy. |
| `AMAZON_PAGE` | `amazon.de`                 | Amazon marketplace domain. `alexaServiceHost` is derived as `alexa.<AMAZON_PAGE>`. |
| `COOKIE_PATH` | `./data/alexa-cookie.json`  | Where the cookie / registration data is persisted so login survives restarts. |

The proxy always binds to `0.0.0.0` so it works inside Docker, but the returned
`proxyUrl` uses `PROXY_HOST`. No secrets are hardcoded.

## HTTP API

| Method & path      | Body                                        | Success            | Notes |
|--------------------|---------------------------------------------|--------------------|-------|
| `GET /health`      | –                                           | `200 {"ok":true}`  | Liveness. |
| `GET /status`      | –                                           | `200 {"loggedIn":boolean,"accountName":string\|null}` | `loggedIn` = a valid cookie is loaded. |
| `POST /login/start`| –                                           | `200 {"proxyUrl":string}` | Open the URL in a browser to log in. `500 {"error"}` on failure. |
| `POST /logout`     | –                                           | `204`              | Deletes stored cookie and drops the in-memory instance. |
| `GET /devices`     | –                                           | `200 [{"serialNumber","name","deviceType","ttsCapable"}]` | `409 {"error":"not logged in"}` if not logged in. |
| `POST /speak`      | `{"serialNumber":string,"text":string}`     | `204`              | Single-device speak, no chime. |
| `POST /announce`   | `{"serialNumbers":string[],"text":string}`  | `204`              | Announcement with chime to one or more devices. |

`ttsCapable` is `true` when the device's capabilities include `AUDIO_PLAYER`,
or when it is an online device that is not a known app/non-speaker family
(mobile app, tablet app, Fire TV, etc.).

## Run locally

```bash
cd alexa-sidecar
npm install
node server.js
```

Then:

```bash
curl http://localhost:3456/health
curl -X POST http://localhost:3456/login/start   # open the returned proxyUrl in a browser
curl http://localhost:3456/status
curl http://localhost:3456/devices
curl -X POST http://localhost:3456/speak \
  -H 'Content-Type: application/json' \
  -d '{"serialNumber":"XXXX","text":"Dinner is ready"}'
curl -X POST http://localhost:3456/announce \
  -H 'Content-Type: application/json' \
  -d '{"serialNumbers":["XXXX"],"text":"Someone is at the door"}'
```

## Run with Docker

```bash
cd alexa-sidecar
docker build -t alexa-sidecar .
docker run --rm \
  -p 3456:3456 -p 3457:3457 \
  -e PROXY_HOST=192.168.1.50 \
  -v "$(pwd)/data:/app/data" \
  alexa-sidecar
```

Set `PROXY_HOST` to an address the browser can reach, and mount a volume at
`/app/data` so the login survives container restarts.
