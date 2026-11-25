'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { isValidSoundId, buildSoundCommands } = require('../sound');

test('buildSoundCommands creates one sound node per device', () => {
  assert.deepEqual(buildSoundCommands(['A1', 'B2'], 'amzn_sfx_doorbell_chime_01'), [
    { command: 'sound', value: 'amzn_sfx_doorbell_chime_01', device: 'A1' },
    { command: 'sound', value: 'amzn_sfx_doorbell_chime_01', device: 'B2' }
  ]);
});

test('isValidSoundId accepts Amazon sound ids and rejects junk', () => {
  assert.ok(isValidSoundId('amzn_sfx_doorbell_chime_01'));
  assert.ok(isValidSoundId('soundbank://soundlibrary/alarms/beeps_and_bloops/bell_02'));
  assert.ok(!isValidSoundId(''));
  assert.ok(!isValidSoundId('a b'));
  assert.ok(!isValidSoundId('"quote"'));
  assert.ok(!isValidSoundId(42));
});
