'use strict';

/**
 * Pause after Amazon throttling ("Too many requests").
 *
 * Amazon throttled the air-quality state query for days (from 2026-11-18) because
 * every minute's poll hit it again - plus alexa-remote2's own retry - so the limit
 * never had a quiet minute to reset. While paused, the sidecar does not ask Amazon
 * at all. The pause doubles on consecutive throttles (15, 30, 60 min, capped) and
 * resets on the first success; normal operation keeps the backend's minute polling.
 */

const FIRST_PAUSE_MS = 15 * 60 * 1000;
const MAX_PAUSE_MS = 60 * 60 * 1000;

const THROTTLE_PATTERN = /too many requests|rate exceeded|throttlingexception/i;

function isThrottleMessage(text) {
  return typeof text === 'string' && THROTTLE_PATTERN.test(text);
}

class ThrottleCooldown {
  constructor(now = Date.now) {
    this.now = now;
    this.nextPauseMs = FIRST_PAUSE_MS;
    this.until = null;
  }

  /** Epoch ms until which requests are paused, or null if Amazon may be asked. */
  blockedUntil() {
    if (this.until !== null && this.now() < this.until) return this.until;
    return null;
  }

  recordThrottle() {
    this.until = this.now() + this.nextPauseMs;
    this.nextPauseMs = Math.min(this.nextPauseMs * 2, MAX_PAUSE_MS);
  }

  recordSuccess() {
    this.until = null;
    this.nextPauseMs = FIRST_PAUSE_MS;
  }
}

module.exports = { ThrottleCooldown, isThrottleMessage };
