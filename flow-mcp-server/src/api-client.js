const DEFAULT_BASE_URL = 'http://localhost:8080/api';
const REQUEST_TIMEOUT_MS = 15000;

/** Non-2xx-Antwort des Backends; body ist geparstes JSON oder roher Text. */
export class ApiError extends Error {
  constructor(status, body) {
    super(`HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

function tryParseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export function createApiClient(baseUrl = process.env.HOUSEHOLD_API_URL || DEFAULT_BASE_URL) {
  const base = baseUrl.replace(/\/+$/, '');

  async function request(method, path, body) {
    const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
    if (process.env.HOUSEHOLD_API_TOKEN) {
      headers['X-API-Token'] = process.env.HOUSEHOLD_API_TOKEN;
    }
    let response;
    try {
      response = await fetch(`${base}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      const reason = error?.cause?.code ?? error?.name ?? 'unbekannt';
      throw new Error(
        `Backend unter ${base} nicht erreichbar (${reason}). Läuft das Spring-Boot-Backend (mvn spring-boot:run)?`
      );
    }
    const text = await response.text();
    const parsed = text ? tryParseJson(text) : null;
    if (!response.ok) {
      throw new ApiError(response.status, parsed);
    }
    return parsed;
  }

  return {
    baseUrl: base,
    get: (path) => request('GET', path),
    post: (path, body) => request('POST', path, body),
    put: (path, body) => request('PUT', path, body),
    delete: (path) => request('DELETE', path),
  };
}
