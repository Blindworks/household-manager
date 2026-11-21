'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { ThrottleCooldown, isThrottleMessage } = require('../throttle');

const MIN = 60 * 1000;

function cooldownAt(startMs) {
  const clock = { now: startMs };
  const cooldown = new ThrottleCooldown(() => clock.now);
  return { cooldown, clock };
}

test('isThrottleMessage recognises Amazon throttling texts', () => {
  assert.ok(isThrottleMessage('{"message":"Too many requests. Try again in some time."}'));
  assert.ok(isThrottleMessage('Rate exceeded'));
  assert.ok(isThrottleMessage('ThrottlingException'));
  assert.ok(!isThrottleMessage('TargetApplianceUnreachable'));
  assert.ok(!isThrottleMessage(null));
});

test('a fresh cooldown lets requests through', () => {
  const { cooldown } = cooldownAt(0);
  assert.equal(cooldown.blockedUntil(), null);
});

test('the first throttle pauses for 15 minutes', () => {
  const { cooldown, clock } = cooldownAt(0);
  cooldown.recordThrottle();
  clock.now = 14 * MIN;
  assert.equal(cooldown.blockedUntil(), 15 * MIN);
  clock.now = 15 * MIN;
  assert.equal(cooldown.blockedUntil(), null);
});

test('consecutive throttles double the pause up to one hour', () => {
  const { cooldown, clock } = cooldownAt(0);
  const pauses = [];
  for (let i = 0; i < 5; i++) {
    const before = clock.now;
    cooldown.recordThrottle();
    pauses.push((cooldown.blockedUntil() - before) / MIN);
    clock.now = cooldown.blockedUntil();
  }
  assert.deepEqual(pauses, [15, 30, 60, 60, 60]);
});

test('a success resets the pause to 15 minutes', () => {
  const { cooldown, clock } = cooldownAt(0);
  cooldown.recordThrottle();
  clock.now = cooldown.blockedUntil();
  cooldown.recordThrottle();
  clock.now = cooldown.blockedUntil();
  cooldown.recordSuccess();
  cooldown.recordThrottle();
  assert.equal(cooldown.blockedUntil() - clock.now, 15 * MIN);
});
