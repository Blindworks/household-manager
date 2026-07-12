'use strict';

/**
 * Alexa sidecar service.
 *
 * Wraps the `alexa-remote2` library and exposes a small HTTP API so the
 * Household-Manager Spring Boot backend can drive Amazon Alexa over plain HTTP:
 * browser-proxy login, device listing and text-to-speech.
 *
 * The alexa-remote2 API used here (verified against the library source):
 *   - alexa.init(options, cb)                     -> initialize / start proxy login
 *   - options.proxyOnly / setupProxy / proxyOwnIp / proxyPort / proxyListenBind
 *                                                 -> browser-login proxy config
 *   - alexa.on('cookie', (cookie, csrf, macDms))  -> fired when a cookie is (re)obtained
 *   - alexa.getDevices(cb) -> cb(err, {devices:[...]})
 *   - alexa.sendSequenceCommand(serial, 'speak', text, cb)          -> single-device TTS
 *   - alexa.sendSequenceCommand([serials], 'announcement', text, cb) -> announcement w/ chime
 */

const fs = require('fs');
const path = require('path');
const express = require('express');
const AlexaRemote = require('alexa-remote2');
const { extractAirQualityMonitors, mapDeviceStates } = require('./smarthome');

// ---------------------------------------------------------------------------
// Configuration (all via env, with sane defaults)
// ---------------------------------------------------------------------------
const PORT = parseInt(process.env.PORT || '3456', 10);
const PROXY_HOST = process.env.PROXY_HOST || 'localhost';
const PROXY_PORT = parseInt(process.env.PROXY_PORT || '3457', 10);
const PROXY_BIND = '0.0.0.0';
const AMAZON_PAGE = process.env.AMAZON_PAGE || 'amazon.de';
const ALEXA_SERVICE_HOST = 'alexa.' + AMAZON_PAGE;
const COOKIE_PATH = process.env.COOKIE_PATH || path.join('.', 'data', 'alexa-cookie.json');

const PROXY_URL = `http://${PROXY_HOST}:${PROXY_PORT}/`;

// ---------------------------------------------------------------------------
// In-memory state
// ---------------------------------------------------------------------------
let alexa = null; // current AlexaRemote instance
let accountName = null; // best-effort human-readable account name

function log(...args) {
  console.log('[alexa-sidecar]', ...args);
}

function isLoggedIn() {
  return !!(alexa && alexa.cookie && alexa.csrf);
}

// ---------------------------------------------------------------------------
// Cookie / registration persistence
// ---------------------------------------------------------------------------
function loadStoredCookie() {
  try {
    if (!fs.existsSync(COOKIE_PATH)) return null;
    const raw = fs.readFileSync(COOKIE_PATH, 'utf8');
    if (!raw || !raw.trim()) return null;
    return JSON.parse(raw);
  } catch (err) {
    log('Failed to read stored cookie:', err.message);
    return null;
  }
}

function saveStoredCookie(data) {
  try {
    fs.mkdirSync(path.dirname(COOKIE_PATH), { recursive: true });
    fs.writeFileSync(COOKIE_PATH, JSON.stringify(data, null, 2), 'utf8');
    log('Cookie/registration data persisted to', COOKIE_PATH);
  } catch (err) {
    log('Failed to persist cookie:', err.message);
  }
}

function deleteStoredCookie() {
  try {
    if (fs.existsSync(COOKIE_PATH)) {
      fs.unlinkSync(COOKIE_PATH);
      log('Deleted stored cookie', COOKIE_PATH);
    }
  } catch (err) {
    log('Failed to delete stored cookie:', err.message);
  }
}

// ---------------------------------------------------------------------------
// alexa-remote2 wiring
// ---------------------------------------------------------------------------
function baseOptions() {
  return {
    amazonPage: AMAZON_PAGE,
    alexaServiceHost: ALEXA_SERVICE_HOST,
    useWsMqtt: false,
    // refresh cookie every 4 days; keeps the session alive across restarts
    cookieRefreshInterval: 4 * 24 * 60 * 60 * 1000,
    logger: (msg) => log(msg)
  };
}

/**
 * Attach the 'cookie' listener that persists freshly obtained registration data.
 * The full `alexa.cookieData` object (localCookie + macDms + tokens) is what we
 * need to re-init without a browser next time.
 */
function attachCookieListener(instance) {
  instance.on('cookie', () => {
    const data = instance.cookieData || (instance.cookie ? { cookie: instance.cookie } : null);
    if (data) saveStoredCookie(data);
    fetchAccountName(instance);
  });
  instance.on('ws-error', (err) => log('ws-error:', err && err.message ? err.message : err));
}

/**
 * Best-effort lookup of a human-readable account name. Returns via side effect
 * on the module-level `accountName`. Amazon's /accounts endpoint returns an
 * array; we take the first entry's accountName/commsId if present.
 */
function fetchAccountName(instance) {
  try {
    instance.getAccount((err, result) => {
      if (err || !Array.isArray(result) || result.length === 0) return;
      const first = result[0];
      accountName = first.accountName || first.commsId || first.email || null;
    });
  } catch (err) {
    // never let account lookup crash anything
    log('getAccount failed:', err.message);
  }
}

/**
 * Initialize alexa-remote2 with a stored cookie (non-proxy). Used on startup.
 */
function initWithStoredCookie(stored) {
  return new Promise((resolve) => {
    const instance = new AlexaRemote();
    attachCookieListener(instance);
    const options = Object.assign(baseOptions(), { cookie: stored });
    instance.init(options, (err) => {
      if (err) {
        log('Init with stored cookie failed:', err.message);
        // keep the instance around only if it actually has a valid cookie
        if (instance.cookie && instance.csrf) {
          alexa = instance;
          return resolve(true);
        }
        return resolve(false);
      }
      alexa = instance;
      log('Initialized from stored cookie; loggedIn =', isLoggedIn());
      resolve(true);
    });
  });
}

/**
 * Start a fresh browser-proxy login. Returns the proxy URL the user must open.
 * The instance is kept in `alexa`; it becomes usable once the user completes
 * the login in the browser (signalled by the 'cookie' event).
 */
function startProxyLogin() {
  const instance = new AlexaRemote();
  attachCookieListener(instance);

  const options = Object.assign(baseOptions(), {
    proxyOnly: true,
    setupProxy: true,
    proxyOwnIp: PROXY_HOST,
    proxyPort: PROXY_PORT,
    proxyListenBind: PROXY_BIND
  });

  // The init callback fires first with a "Please open http://..." Error while
  // the proxy waits for the browser login. That is expected, not fatal. It
  // fires again (err === undefined) once login succeeds and the instance is
  // fully initialized.
  instance.init(options, (err) => {
    if (err) {
      log('Proxy login pending / notice:', err.message);
      return;
    }
    log('Proxy login completed; loggedIn =', isLoggedIn());
  });

  // Adopt this instance as the current one right away so /status reflects the
  // in-progress login and the 'cookie' event persists against it.
  alexa = instance;
  return PROXY_URL;
}

// ---------------------------------------------------------------------------
// TTS capability heuristic
// ---------------------------------------------------------------------------
// Device families that are apps / non-speakers and cannot render TTS.
const NON_SPEAKER_FAMILIES = new Set([
  'MOBILE',
  'TABLET',
  'APP',
  'REALM',
  'FIRE_TV',
  'THIRD_PARTY_AVS_MEDIA_DISPLAY',
  'AMAZONMOBILEMUSIC_ANDROID',
  'AMAZONMOBILEMUSIC_IOS'
]);

function isTtsCapable(device) {
  const caps = Array.isArray(device.capabilities) ? device.capabilities : [];
  if (caps.includes('AUDIO_PLAYER')) return true;
  const family = device.deviceFamily || '';
  if (NON_SPEAKER_FAMILIES.has(family)) return false;
  // Default: true for real, online devices that aren't a known app family.
  return device.online !== false;
}

// ---------------------------------------------------------------------------
// HTTP API
// ---------------------------------------------------------------------------
const app = express();
app.use(express.json());

app.get('/health', (req, res) => {
  res.status(200).json({ ok: true });
});

app.get('/status', (req, res) => {
  res.status(200).json({
    loggedIn: isLoggedIn(),
    accountName: isLoggedIn() ? accountName : null
  });
});

app.post('/login/start', (req, res) => {
  try {
    const proxyUrl = startProxyLogin();
    res.status(200).json({ proxyUrl });
  } catch (err) {
    log('login/start failed:', err.message);
    res.status(500).json({ error: err.message });
  }
});

app.post('/logout', (req, res) => {
  try {
    deleteStoredCookie();
    if (alexa) {
      try {
        if (typeof alexa.stop === 'function') alexa.stop();
      } catch (e) {
        // ignore teardown errors
      }
    }
    alexa = null;
    accountName = null;
    res.status(204).end();
  } catch (err) {
    log('logout failed:', err.message);
    res.status(500).json({ error: err.message });
  }
});

app.get('/devices', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  alexa.getDevices((err, result) => {
    if (err) {
      log('getDevices failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    const devices = (result && Array.isArray(result.devices) ? result.devices : [])
      .map((d) => ({
        serialNumber: d.serialNumber,
        name: d.accountName,
        deviceType: d.deviceType,
        ttsCapable: isTtsCapable(d)
      }));
    res.status(200).json(devices);
  });
});

app.post('/speak', (req, res) => {
  const { serialNumber, text } = req.body || {};
  if (!serialNumber || typeof text !== 'string' || text.length === 0) {
    return res.status(400).json({ error: 'serialNumber and non-empty text are required' });
  }
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  alexa.sendSequenceCommand(serialNumber, 'speak', text, (err) => {
    if (err) {
      log('speak failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    res.status(204).end();
  });
});

app.post('/announce', (req, res) => {
  const { serialNumbers, text } = req.body || {};
  if (!Array.isArray(serialNumbers) || serialNumbers.length === 0 || typeof text !== 'string' || text.length === 0) {
    return res.status(400).json({ error: 'serialNumbers (non-empty array) and non-empty text are required' });
  }
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  // 'announcement' plays the notification chime; an array target announces to
  // all listed devices at once.
  alexa.sendSequenceCommand(serialNumbers, 'announcement', text, (err) => {
    if (err) {
      log('announce failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    res.status(204).end();
  });
});

// ---------------------------------------------------------------------------
// Smart home: Amazon Air Quality Monitors
// ---------------------------------------------------------------------------
// Discovery is cached because the backend polls every few minutes and the
// monitor list / instance mapping changes practically never.
const AQM_CACHE_TTL_MS = 10 * 60 * 1000;
let aqmCache = { monitors: null, fetchedAt: 0 };

function getAirQualityMonitors(callback) {
  if (aqmCache.monitors && Date.now() - aqmCache.fetchedAt < AQM_CACHE_TTL_MS) {
    return callback(null, aqmCache.monitors);
  }
  alexa.getSmarthomeDevices((err, locationDetails) => {
    if (err) return callback(err);
    try {
      const monitors = extractAirQualityMonitors(locationDetails);
      aqmCache = { monitors, fetchedAt: Date.now() };
      callback(null, monitors);
    } catch (mapErr) {
      callback(mapErr);
    }
  });
}

// Debug/spike endpoint: raw discovery payload to inspect the real format.
app.get('/smarthome/raw', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  alexa.getSmarthomeDevices((err, locationDetails) => {
    if (err) {
      log('smarthome/raw failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    res.status(200).json(locationDetails);
  });
});

app.get('/smarthome/air-quality-monitors', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  getAirQualityMonitors((err, monitors) => {
    if (err) {
      log('air-quality-monitors failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    res.status(200).json(monitors);
  });
});

app.get('/smarthome/air-quality-monitors/state', (req, res) => {
  if (!isLoggedIn()) {
    return res.status(409).json({ error: 'not logged in' });
  }
  getAirQualityMonitors((err, monitors) => {
    if (err) {
      log('air-quality state (discovery) failed:', err.message);
      return res.status(500).json({ error: err.message });
    }
    if (monitors.length === 0) {
      return res.status(200).json([]);
    }
    const ids = monitors.map((m) => m.applianceId);
    alexa.querySmarthomeDevices(ids, 'APPLIANCE', (queryErr, stateResponse) => {
      if (queryErr) {
        log('air-quality state (query) failed:', queryErr.message);
        return res.status(500).json({ error: queryErr.message });
      }
      try {
        res.status(200).json(mapDeviceStates(stateResponse, monitors));
      } catch (mapErr) {
        log('air-quality state (mapping) failed:', mapErr.message);
        res.status(500).json({ error: mapErr.message });
      }
    });
  });
});

// Catch-all JSON error handler so a bad request never crashes the process.
app.use((err, req, res, next) => {
  log('Unhandled request error:', err.message);
  if (res.headersSent) return next(err);
  res.status(400).json({ error: err.message });
});

// ---------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------
process.on('uncaughtException', (err) => {
  log('uncaughtException:', err && err.stack ? err.stack : err);
});
process.on('unhandledRejection', (reason) => {
  log('unhandledRejection:', reason);
});

function start() {
  const stored = loadStoredCookie();
  if (stored) {
    log('Found stored cookie, attempting auto-init...');
    initWithStoredCookie(stored).catch((err) => log('Auto-init error:', err.message));
  } else {
    log('No stored cookie found; login required via POST /login/start');
  }

  app.listen(PORT, PROXY_BIND, () => {
    log(`HTTP API listening on port ${PORT}`);
    log(`Proxy login URL will be ${PROXY_URL} (bind ${PROXY_BIND}:${PROXY_PORT})`);
    log(`Amazon page: ${AMAZON_PAGE}, service host: ${ALEXA_SERVICE_HOST}`);
  });
}

// Only start the server when run directly, so the file can be required in tests.
if (require.main === module) {
  start();
}

module.exports = { app, isTtsCapable };
