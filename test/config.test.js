import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeConfig, validateConfig, publicConfig } from '../trigger/lib/config.js'
import { baseConfig } from './helpers.js'

const NOW = Date.parse('2026-11-01T12:00:00Z')

test('migrates the legacy example config shape', () => {
  const c = normalizeConfig({
    name: 'Device Name', wakeUpTimeout: 5, startBefore: 20, endAfter: 30,
    cameraType: 'generic', genericReleaseGpioPin: 25, genericFocusGpioPin: 23,
    logFile: 'log.txt', zcamCameraIp: '10.0.0.5',
    attempts: ['2026-11-02T17:39:00.000Z'],
  })
  assert.equal(c.generic.releaseGpioPin, 25)
  assert.equal(c.generic.focusGpioPin, 23)
  assert.equal(c.zcam.cameraIp, '10.0.0.5')
  assert.deepEqual(c.attempts, [{ name: 'Attempt 1', time: '2026-11-02T17:39:00.000Z' }])
  assert.equal(c.genericReleaseGpioPin, undefined)
})

test('migrates the README port names and numeric strings', () => {
  const c = normalizeConfig({ generic: { releaseGpioPort: '25', focusGpioPort: 23 }, startBefore: '20' })
  assert.equal(c.generic.releaseGpioPin, 25)
  assert.equal(c.generic.focusGpioPin, 23)
  assert.equal(c.startBefore, 20)
})

test('a valid config passes', () => {
  assert.deepEqual(validateConfig(normalizeConfig(baseConfig(NOW)), { now: NOW }), [])
})

test('reports each problem with its field', () => {
  const bad = normalizeConfig(baseConfig(NOW, {
    cameraType: 'zcam', zcam: {}, startBefore: -1, logFile: '',
    attempts: [
      { name: 'a', time: 'nope' },
      { name: 'b', time: new Date(NOW - 1000).toISOString() },
      { name: 'c', time: new Date(NOW + 5000).toISOString() },
      { name: 'd', time: new Date(NOW + 5000).toISOString() },
    ],
  }))
  const fields = validateConfig(bad, { now: NOW }).map((e) => e.field)
  for (const f of ['zcam.cameraIp', 'startBefore', 'logFile', 'attempts[0].time', 'attempts[1].time', 'attempts[3].time']) {
    assert.ok(fields.includes(f), `missing ${f}: ${fields}`)
  }
})

test('generic requires a release pin', () => {
  const c = normalizeConfig(baseConfig(NOW, { generic: {} }))
  assert.deepEqual(validateConfig(c, { now: NOW }).map((e) => e.field), ['generic.releaseGpioPin'])
})

test('validation errors carry a machine-readable reason', () => {
  const reasons = (c) => Object.fromEntries(validateConfig(normalizeConfig(c), { now: NOW }).map((e) => [e.field, e.reason]))
  const r = reasons(baseConfig(NOW, {
    cameraType: 'zcam', zcam: {}, endAfter: 9999,
    attempts: [
      { name: 'a', time: new Date(NOW - 1000).toISOString() },
      { name: 'b', time: new Date(NOW + 5000).toISOString() },
      { name: 'c', time: new Date(NOW + 5000).toISOString() },
      { name: 'x'.repeat(33), time: new Date(NOW + 9000).toISOString() },
    ],
  }))
  assert.equal(r['zcam.cameraIp'], 'required')
  assert.equal(r.endAfter, 'range')
  assert.equal(r['attempts[0].time'], 'past')
  assert.equal(r['attempts[2].time'], 'duplicate')
  assert.equal(r['attempts[3].name'], 'range')
  assert.equal(reasons(baseConfig(NOW, { attempts: Array.from({ length: 31 }, (_, i) => ({ name: 'n', time: new Date(NOW + (i + 1) * 3600000).toISOString() })) })).attempts, 'too_many')
})

test('names are limited in UTF-8 bytes, not characters', () => {
  const name = '🚀'.repeat(9) // 9 characters, 36 bytes
  const errs = validateConfig(normalizeConfig(baseConfig(NOW, { name })), { now: NOW })
  assert.deepEqual(errs.map((e) => e.field), ['name'])
})

test('publicConfig strips the secret', () => {
  const c = publicConfig({ ...baseConfig(NOW), bleSecret: 'aa', deviceName: 'x' })
  assert.equal(c.bleSecret, undefined)
  assert.equal(c.deviceName, undefined)
})
