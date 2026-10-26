import test from 'node:test'
import assert from 'node:assert/strict'
import { createHmac, randomBytes } from 'crypto'
import { makeDevice, TestClient, SECRET } from './helpers.js'
import { encodeConfig, encodeRequest, decodeStatus, decodeDeviceInfo } from '../trigger/ble/codec.js'
import { FIELD, REASON } from '../trigger/ble/protocol.js'

const paired = async (overrides, opts) => {
  const dev = await makeDevice(overrides)
  dev.scheduler.config.bleSecret = SECRET
  const client = new TestClient(dev.ble, opts)
  const { res } = await client.authenticate(SECRET)
  assert.equal(res.ok, true)
  return { dev, client }
}

const hex = (n) => randomBytes(n).toString('hex')
const status = (dev) => decodeStatus(dev.ble.status.toBinary())
const iso = (ms) => new Date(ms).toISOString()
const secs = (ms) => Math.floor(ms / 1000)

test('hello reports pairing state and a fresh nonce', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const a = await c.request('hello')
  const b = await c.request('hello')
  assert.equal(a.ok, true)
  assert.equal(a.fields.v, 2)
  assert.equal(a.fields.paired, false)
  assert.equal(a.fields.maxMessage, 2048)
  assert.equal(a.fields.nowMs, dev.clock.now)
  assert.notEqual(a.fields.nonceHex, b.fields.nonceHex)
})

test('Device Info is binary and names the device', async () => {
  const dev = await makeDevice()
  assert.deepEqual(decodeDeviceInfo(dev.ble.deviceInfo()), {
    v: 2, id: 'ABC123', cam: 'generic', cams: ['generic', 'zcam'], fw: process.env.npm_package_version ?? '1.0.0', name: 'Test',
  })
})

test('the advertised name keeps the id when the name is long', async () => {
  const dev = await makeDevice({ name: 'é'.repeat(32) })
  const name = dev.ble.advertisedName()
  assert.ok(Buffer.byteLength(name) <= 29, name)
  assert.ok(name.endsWith('-ABC123'))
  assert.equal((await makeDevice({ name: '' })).ble.advertisedName(), 'ABC123')
})

test('everything but the open ops needs auth', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const withFields = {
    'time.set': { nowMs: 1 },
    'config.set': { config: { cameraType: 'generic', wakeUpTimeout: 1, startBefore: 1, endAfter: 1, cameraIp: null, name: '', attempts: [] } },
    'logs.get': { after: 0, limit: 1 },
  }
  for (const op of ['config.get', 'jobs.get', 'camera.start', 'test.run', 'logs.get', 'time.set', 'config.set']) {
    const r = await c.request(op, withFields[op])
    assert.equal(r.ok, false, op)
    assert.equal(r.code, 'unauthorized', op)
  }
  const t = await c.request('time.get')
  assert.equal(t.ok, true)
  assert.equal(t.fields.nowMs, dev.clock.now)
})

test('unknown ops are not_supported', async () => {
  const { client } = await paired()
  const r = await client.requestRaw(Buffer.from([0x7f, 9]), 9)
  assert.equal(r.code, 'not_supported')
  assert.equal(r.id, 9)
  // 0x06 was status.get in version 1
  assert.equal((await client.requestRaw(Buffer.from([0x06, 10]), 10)).code, 'not_supported')
})

test('pairing requires the physical window, then stores the secret', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const secret = randomBytes(16)

  const closed = await c.request('auth.pair', { secretHex: secret.toString('hex') })
  assert.equal(closed.code, 'pairing_closed')

  dev.ble.pairing.open()
  const short = await c.requestRaw(Buffer.from([0x03, 20, 1, 2, 3]), 20)
  assert.equal(short.code, 'bad_request')

  const ok = await c.request('auth.pair', { secretHex: secret.toString('hex') })
  assert.equal(ok.ok, true)
  assert.equal(dev.ble.pairing.isOpen, false) // single use
  assert.equal((await c.request('config.get')).ok, true) // session is authed
  assert.equal((await dev.readConfig()).bleSecret, secret.toString('hex'))

  // a second client can authenticate with it, and cannot re-pair
  const c2 = new TestClient(dev.ble)
  const { res } = await c2.authenticate(secret.toString('hex'))
  assert.equal(res.ok, true)
  assert.equal((await c2.request('auth.pair', { secretHex: secret.toString('hex') })).code, 'pairing_closed')
})

test('auth verifies both directions and rejects bad proofs', async () => {
  const dev = await makeDevice()
  dev.scheduler.config.bleSecret = SECRET
  const c = new TestClient(dev.ble)
  const { res, nonce, clientNonce, key } = await c.authenticate(SECRET)
  const expected = createHmac('sha256', key).update('kairos-auth-v2-srv').update(nonce).update(clientNonce).digest('hex')
  assert.equal(res.fields.proofHex, expected)

  const c2 = new TestClient(dev.ble)
  await c2.request('hello')
  const r = await c2.request('auth', { clientNonceHex: hex(16), proofHex: hex(32) })
  assert.equal(r.code, 'unauthorized')
  assert.equal((await c2.request('config.get')).code, 'unauthorized')
})

test('auth before hello, or with an unpaired device, fails', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const early = await c.request('auth', { clientNonceHex: hex(16), proofHex: hex(32) })
  assert.equal(early.code, 'bad_request')

  await c.request('hello')
  const r = await c.request('auth', { clientNonceHex: hex(16), proofHex: hex(32) })
  assert.equal(r.code, 'unauthorized')
})

test('five failures lock auth out, with a retryAfter', async () => {
  const dev = await makeDevice()
  dev.scheduler.config.bleSecret = SECRET
  const c = new TestClient(dev.ble)
  await c.request('hello')
  const bad = () => c.request('auth', { clientNonceHex: hex(16), proofHex: hex(32) })
  for (let i = 0; i < 5; i++) assert.equal((await bad()).code, 'unauthorized')
  const locked = await bad()
  assert.equal(locked.code, 'locked_out')
  assert.ok(locked.detail.retryAfter > 0)
  // even a correct proof is refused while locked
  const { res } = await c.authenticate(SECRET)
  assert.equal(res.code, 'locked_out')
})

test('the Status value carries phase, next event and the next attempt index', async () => {
  const { dev } = await paired()
  const s = status(dev)
  assert.equal(dev.ble.status.toBinary().length, 13)
  assert.equal(s.flags & 0b10, 0b10) // camera ready
  assert.equal(s.phase, 'idle')
  assert.equal(s.configRev, 1)
  assert.equal(s.nextEventType, 'wake')
  assert.equal(s.nextEventAt, secs(dev.clock.now + 3_600_000 - 25_000))
  assert.equal(s.attemptsRemaining, 1)
  assert.equal(s.nextAttempt, 0)
})

test('config.get returns the wire config; config.set replaces, saves and reschedules', async () => {
  const { dev, client } = await paired()
  const got = await client.request('config.get')
  const cfg = got.fields.config
  assert.equal(cfg.name, 'Test')
  assert.equal(cfg.cameraType, 'generic')
  assert.equal('releasePin' in cfg, false) // the pins are wiring, not part of the protocol
  assert.equal(cfg.attempts.length, 1)

  const t1 = secs(dev.clock.now + 7_200_000)
  const t2 = secs(dev.clock.now + 9_000_000)
  const next = { ...cfg, startBefore: 10, attempts: [{ name: 'A', time: t1 }, { name: 'B', time: t2 }] }
  const before = dev.timers.length
  const set = await client.request('config.set', { config: next })
  assert.equal(set.ok, true, JSON.stringify(set))
  assert.equal(set.fields.configRev, 2)

  const jobs = await client.request('jobs.get')
  assert.deepEqual(jobs.fields.states, ['pending', 'pending'])
  const job = dev.scheduler.jobs[0]
  assert.equal(secs(job.config.recordTime.getTime()), t1 - 10)

  const saved = await dev.readConfig()
  assert.equal(saved.deviceId, 'ABC123') // device-owned fields are not on the wire
  assert.equal(saved.bleSecret, SECRET)
  assert.equal(saved.logFile, 'log.txt')
  assert.equal(saved.configRev, 2)
  assert.deepEqual(saved.attempts, [{ name: 'A', time: iso(t1 * 1000) }, { name: 'B', time: iso(t2 * 1000) }])

  // old timers cancelled, 3 new per attempt
  assert.ok(dev.timers.slice(0, before).every((t) => t.cancelled))
  assert.equal(dev.timers.length - before, 6)
  assert.equal(status(dev).configRev, 2)
})

test('config.set round-trips the camera ip and unicode names, and keeps the pins from config.json', async () => {
  const { dev, client } = await paired()
  const cfg = {
    cameraType: 'zcam', wakeUpTimeout: 7, startBefore: 8, endAfter: 9,
    cameraIp: '192.168.1.50', name: 'Café 🚀',
    attempts: [{ name: 'Lançamento', time: secs(dev.clock.now + 7_200_000) }],
  }
  assert.equal((await client.request('config.set', { config: cfg })).ok, true)
  assert.deepEqual((await client.request('config.get')).fields.config, cfg)
  assert.equal((await dev.readConfig()).generic.releaseGpioPin, 25)
})

test('a config with too many attempts or an unknown layout is a bad_request', async () => {
  const { dev, client } = await paired()
  const cfg = (await client.request('config.get')).fields.config
  const body = encodeConfig({ ...cfg, attempts: [] })
  body[body.length - 1] = 31 // count > 30
  const r = await client.requestRaw(Buffer.concat([Buffer.from([0x08, 30]), body]), 30)
  assert.equal(r.code, 'bad_request')
  const wrongLayout = Buffer.from(encodeConfig(cfg))
  wrongLayout[0] = 9
  assert.equal((await client.requestRaw(Buffer.concat([Buffer.from([0x08, 31]), wrongLayout]), 31)).code, 'bad_request')
  assert.equal((await dev.readConfig()).attempts.length, 1) // untouched
})

test('config.set validation errors name the field and leave state untouched', async () => {
  const { dev, client } = await paired()
  const cfg = (await client.request('config.get')).fields.config

  const past = await client.request('config.set', { config: { ...cfg, attempts: [{ name: 'x', time: secs(dev.clock.now) - 1 }] } })
  assert.equal(past.code, 'invalid_config')
  assert.deepEqual(past.detail, { field: FIELD.attemptTime, index: 0, reason: REASON.past })
  assert.equal((await dev.readConfig()).attempts[0].name, 'Attempt 1')

  // the wake-up time has already passed
  const soon = await client.request('config.set', { config: { ...cfg, attempts: [{ name: 'x', time: secs(dev.clock.now) + 10 }] } })
  assert.deepEqual(soon.detail, { field: FIELD.attemptTime, index: 0, reason: REASON.too_soon })

  const dup = await client.request('config.set', {
    config: { ...cfg, attempts: [{ name: 'a', time: secs(dev.clock.now) + 7200 }, { name: 'b', time: secs(dev.clock.now) + 7200 }] },
  })
  assert.deepEqual(dup.detail, { field: FIELD.attemptTime, index: 1, reason: REASON.duplicate })

  const noIp = await client.request('config.set', { config: { ...cfg, cameraType: 'zcam', cameraIp: null } })
  assert.deepEqual(noIp.detail, { field: FIELD.cameraIp, index: 0, reason: REASON.required })

  const range = await client.request('config.set', { config: { ...cfg, endAfter: 5000 } })
  assert.deepEqual(range.detail, { field: FIELD.endAfter, index: 0, reason: REASON.range })
})

test('a full config fragments across a 23-byte MTU and still round-trips', async () => {
  const { dev, client } = await paired(undefined, { mtu: 23 })
  const attempts = Array.from({ length: 30 }, (_, i) => ({ name: `Attempt ${i + 1}`.padEnd(32, '.'), time: secs(dev.clock.now) + (i + 1) * 3600 }))
  const cfg = (await client.request('config.get')).fields.config
  const before = client.notifications.length
  const r = await client.request('config.set', { config: { ...cfg, attempts } })
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.equal(r.fields.configRev, 2)
  const jobs = await client.request('jobs.get')
  assert.equal(jobs.fields.states.length, 30)
  const back = (await client.request('config.get')).fields.config
  assert.deepEqual(back.attempts, attempts)
  assert.ok(client.notifications.length - before > 30) // many fragments
  assert.ok(client.notifications.every((n) => n.length <= 20))
})

test('time.set applies the clock, re-plans timers, and reports drift', async () => {
  const { dev, client } = await paired()
  const before = dev.timers.length
  const target = dev.clock.now - 418
  const r = await client.request('time.set', { nowMs: target })
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.equal(r.fields.offsetMs, 418)
  assert.equal(r.fields.nowMs, dev.clock.now)
  assert.deepEqual(dev.system.timeSet, [target])
  assert.ok(dev.timers.length > before) // rescheduled
  // clock-valid flag shows in Status
  assert.equal(status(dev).flags & 0b1000, 0b1000)
})

test('manual camera control works when idle and is idempotent', async () => {
  const { dev, client } = await paired()
  assert.equal((await client.request('camera.wake')).ok, true)
  assert.equal((await client.request('camera.start')).fields.recording, true)
  assert.equal(status(dev).phase, 'recording')
  assert.equal((await client.request('camera.stop')).fields.recording, false)
  assert.equal((await client.request('camera.sleep')).ok, true)
  assert.deepEqual(dev.scheduler.cam.calls, ['wake', 'start', 'stop', 'sleep'])
})

test('a scheduled attempt walks through its phases, emits events, and blocks changes', async () => {
  const { dev, client } = await paired()
  const [wake, start, stop] = dev.timers
  const cfg = (await client.request('config.get')).fields.config

  await wake.fn()
  const evWake = await client.event('job')
  assert.deepEqual(evWake.fields, { attempt: 0, step: 'wake', at: secs(dev.clock.now) })
  assert.equal(status(dev).phase, 'awake')

  // manual control, config and time are refused mid-attempt
  assert.equal((await client.request('camera.stop')).code, 'busy')
  assert.equal((await client.request('config.set', { config: cfg })).code, 'busy')
  assert.equal((await client.request('time.set', { nowMs: dev.clock.now })).code, 'busy')
  assert.equal((await client.request('test.run')).code, 'busy')

  await start.fn()
  assert.equal(status(dev).phase, 'recording')
  assert.equal(dev.scheduler.jobs[0].state, 'active')
  assert.deepEqual((await client.request('jobs.get')).fields.states, ['active'])

  await stop.fn()
  assert.equal(dev.scheduler.jobs[0].state, 'complete')
  assert.equal(status(dev).phase, 'idle')
  assert.deepEqual(dev.scheduler.cam.calls, ['wake', 'start', 'stop', 'sleep'])
  assert.deepEqual((await client.request('jobs.get')).fields.states, ['complete'])
  const s = status(dev)
  assert.equal(s.nextEventType, 'none')
  assert.equal(s.nextAttempt, null)
})

test('events only reach authenticated clients', async () => {
  const dev = await makeDevice()
  dev.scheduler.config.bleSecret = SECRET
  const anon = new TestClient(dev.ble)
  const authed = new TestClient(dev.ble)
  await authed.authenticate(SECRET)
  await dev.timers[0].fn()
  await authed.event('job')
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(anon.events.length, 0)
})

test('config changes are pushed as events', async () => {
  const { dev, client } = await paired()
  const other = new TestClient(dev.ble)
  await other.authenticate(SECRET)
  const cfg = (await client.request('config.get')).fields.config
  await client.request('config.set', { config: { ...cfg, name: 'Renamed' } })
  assert.deepEqual((await other.event('config')).fields, { configRev: 2 })
})

test('test.run acknowledges at once and streams progress', async () => {
  const { dev, client } = await paired()
  const r = await client.request('test.run')
  assert.equal(r.ok, true)
  await client.event('test')
  assert.equal(status(dev).phase, 'test')
  await new Promise((res) => {
    const check = () => (client.events.some((e) => e.ev === 'test' && e.fields.step === 'done') ? res() : setTimeout(check, 50))
    check()
  })
  const steps = client.events.filter((e) => e.ev === 'test').map((e) => e.fields.step)
  assert.deepEqual(steps, ['wake', 'start', 'stop', 'sleep', 'done'])
  assert.equal(client.events.find((e) => e.fields.step === 'done').fields.ok, true)
  assert.equal(dev.scheduler.phase, 'idle')
})

test('logs.get pages by seq; logs.subscribe replays then streams', async () => {
  const { dev, client } = await paired()
  for (let i = 0; i < 30; i++) dev.logger.log('test', `line ${i}`)
  const first = await client.request('logs.get', { after: 0, limit: 10 })
  assert.equal(first.fields.lines.length, 10)
  assert.equal(first.fields.more, true)
  assert.equal(first.fields.last, first.fields.lines[9].seq)
  assert.deepEqual(Object.keys(first.fields.lines[0]).sort(), ['msg', 'ns', 'seq', 'ts'])
  assert.ok(first.fields.lines.every((l) => l.ns.length > 0))

  const rest = await client.request('logs.get', { after: first.fields.last, limit: 100 })
  assert.equal(rest.fields.more, false)
  assert.equal(rest.fields.lines.at(-1).seq, dev.logger.seq)

  const last = dev.logger.seq
  dev.logger.log('test', 'missed while away')
  assert.equal((await client.request('logs.subscribe', { enabled: true, after: last })).ok, true)
  const replay = await client.event('log')
  assert.equal(replay.fields.msg, 'missed while away')
  dev.logger.log('test', 'live')
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(client.events.some((e) => e.ev === 'log' && e.fields.msg === 'live'))

  await client.request('logs.subscribe', { enabled: false, after: 0 })
  const n = client.events.length
  dev.logger.log('test', 'after unsubscribe')
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(client.events.length, n)
})

test('long log messages are cut to 120 bytes', async () => {
  const { dev, client } = await paired()
  dev.logger.log('a-very-long-namespace', 'é'.repeat(100))
  const r = await client.request('logs.get', { after: dev.logger.seq - 1, limit: 1 })
  const line = r.fields.lines[0]
  assert.ok(Buffer.byteLength(line.msg) <= 120)
  assert.ok(Buffer.byteLength(line.ns) <= 8)
})

test('malformed input gets an error response, not a crash', async () => {
  const { client } = await paired()
  const errs = []
  client.waiters.push((m) => m.ok === false && m.id === 0 && errs.push(m.code))

  const raw = (payload) => { const h = Buffer.alloc(3); h[0] = 0x80; h.writeUInt16BE(payload.length, 1); client.writeRaw(Buffer.concat([h, payload])) }
  raw(Buffer.from([0x01])) // too short to carry an id
  raw(Buffer.from([0x05, 3, 1])) // time.set with a short body: the error uses the id it could read
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(errs.includes('bad_request'))

  // oversize declared length
  client.writeRaw(Buffer.from([0x80, 0xff, 0xff, 1, 2, 3]))
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(errs.includes('too_large'))
  // and the link still works
  assert.equal((await client.request('config.get')).ok, true)
})

test('one request at a time: a second in flight is busy', async () => {
  const { dev, client } = await paired()
  dev.controller.setTime = () => new Promise((r) => setTimeout(() => r({ offsetMs: 0 }), 50))
  const slow = client.request('time.set', { nowMs: Date.now() })
  const second = await client.request('config.get')
  assert.equal(second.code, 'busy')
  assert.equal((await slow).ok, true)
})

test('a stalled reassembly times out with an error', async () => {
  const dev = await makeDevice()
  const client = new TestClient(dev.ble)
  client.link.reassemblyTimeoutMs = 30
  const errP = new Promise((res) => client.waiters.push((m) => m.ok === false && res(m)))
  client.writeRaw(Buffer.from([0x80, 0x00, 0x40, 1, 2, 3]))
  const e = await errP
  assert.equal(e.code, 'bad_request')
})

test('pairing flag shows in Status while the window is open', async () => {
  const dev = await makeDevice()
  assert.equal(status(dev).flags & 0b100, 0)
  dev.ble.pairing.open()
  assert.equal(status(dev).flags & 0b100, 0b100)
  dev.ble.pairing.close()
  assert.equal(status(dev).flags & 0b100, 0)
})

test('attempts whose wake-up time already passed are marked missed', async () => {
  const dev = await makeDevice()
  dev.clock.now += 3_600_000 - 10_000 // 10 s before launch: wake was 25 s before
  dev.scheduler.reinitialize()
  assert.equal(dev.scheduler.jobs[0].state, 'missed')
  assert.equal(dev.scheduler.attemptsRemaining, 0)
  assert.equal(dev.scheduler.next, null)
})

test('encodeRequest and the link agree on the envelope', () => {
  assert.deepEqual([...encodeRequest('camera.start', 12)], [0x11, 12])
})
