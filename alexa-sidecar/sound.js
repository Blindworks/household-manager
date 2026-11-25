'use strict';

/**
 * Builds the alexa-remote2 multi-sequence for a short sound on several Echo
 * devices at once. A ParallelNode lets all devices start together with a single
 * request to Amazon (latency matters: the sound is press feedback for a button).
 * Each node carries its own device, so every Echo plays it, not only the first.
 */
const SOUND_ID_PATTERN = /^[A-Za-z0-9_:/.\-]{1,200}$/;

function isValidSoundId(sound) {
  return typeof sound === 'string' && SOUND_ID_PATTERN.test(sound);
}

function buildSoundCommands(serialNumbers, sound) {
  return serialNumbers.map((serialNumber) => ({ command: 'sound', value: sound, device: serialNumber }));
}

module.exports = { isValidSoundId, buildSoundCommands };
