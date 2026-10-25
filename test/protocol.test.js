import test from 'node:test'
import assert from 'node:assert/strict'
import { createHmac, randomBytes } from 'crypto'
import { makeDevice, TestClient, SECRET } from './helpers.js'

const paired = async (overrides, opts) => {
  const dev = await makeDevice(overrides)
  dev.scheduler.config.bleSecret = SECRET
  const client = new TestClient(dev.ble, opts)
  const { res } = await client.authenticate(SECRET)
  assert.equal(res.ok, true)
  return { dev, client }
}

test('hello reports pairing state and a fresh nonce', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const a = await c.request('hello', {})
  const b = await c.request('hello', {})
  assert.equal(a.ok, true)
  assert.equal(a.d.v, 1)
  assert.equal(a.d.paired, false)
  assert.equal(a.d.maxMessage, 4096)
  assert.notEqual(a.d.nonce, b.d.nonce)
})

test('everything but the open ops needs auth', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  for (const op of ['status.get', 'config.get', 'jobs.get', 'camera.start', 'test.run', 'logs.get', 'time.set', 'config.set']) {
    const r = await c.request(op, {})
    assert.equal(r.ok, false, op)
    assert.equal(r.err.code, 'unauthorized', op)
  }
  const t = await c.request('time.get', {})
  assert.equal(t.ok, true)
  assert.equal(t.d.now, new Date(dev.clock.now).toISOString())
})

test('unknown ops are not_supported, and prototype names do not leak', async () => {
  const { client } = await paired()
  assert.equal((await client.request('nope')).err.code, 'not_supported')
  assert.equal((await client.request('constructor')).err.code, 'not_supported')
})

test('pairing requires the physical window, then stores the secret', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const secret = randomBytes(16)

  const closed = await c.request('auth.pair', { secret: secret.toString('base64') })
  assert.equal(closed.err.code, 'pairing_closed')

  dev.ble.pairing.open()
  const bad = await c.request('auth.pair', { secret: 'AAAA' })
  assert.equal(bad.err.code, 'bad_request')

  const ok = await c.request('auth.pair', { secret: secret.toString('base64') })
  assert.equal(ok.ok, true)
  assert.equal(dev.ble.pairing.isOpen, false) // single use
  assert.equal((await c.request('status.get')).ok, true) // session is authed
  assert.equal((await dev.readConfig()).bleSecret, secret.toString('hex'))

  // a second client can authenticate with it, and cannot re-pair
  const c2 = new TestClient(dev.ble)
  const { res } = await c2.authenticate(secret.toString('hex'))
  assert.equal(res.ok, true)
  assert.equal((await c2.request('auth.pair', { secret: secret.toString('base64') })).err.code, 'pairing_closed')
})

test('auth verifies both directions and rejects bad proofs', async () => {
  const dev = await makeDevice()
  dev.scheduler.config.bleSecret = SECRET
  const c = new TestClient(dev.ble)
  const { res, nonce, clientNonce, key } = await c.authenticate(SECRET)
  const expected = createHmac('sha256', key).update('kairos-auth-v1-srv').update(nonce).update(clientNonce).digest('base64')
  assert.equal(res.d.proof, expected)

  const c2 = new TestClient(dev.ble)
  await c2.request('hello')
  const r = await c2.request('auth', { clientNonce: randomBytes(16).toString('base64'), proof: randomBytes(32).toString('base64') })
  assert.equal(r.err.code, 'unauthorized')
  assert.equal((await c2.request('status.get')).err.code, 'unauthorized')
})

test('auth before hello, or with an unpaired device, fails', async () => {
  const dev = await makeDevice()
  const c = new TestClient(dev.ble)
  const early = await c.request('auth', { clientNonce: randomBytes(16).toString('base64'), proof: randomBytes(32).toString('base64') })
  assert.equal(early.err.code, 'bad_request')

  await c.request('hello')
  const r = await c.request('auth', { clientNonce: randomBytes(16).toString('base64'), proof: randomBytes(32).toString('base64') })
  assert.equal(r.err.code, 'unauthorized')
})

test('five failures lock auth out, with a retryAfter', async () => {
  const dev = await makeDevice()
  dev.scheduler.config.bleSecret = SECRET
  const c = new TestClient(dev.ble)
  await c.request('hello')
  const bad = () => c.request('auth', { clientNonce: randomBytes(16).toString('base64'), proof: randomBytes(32).toString('base64') })
  for (let i = 0; i < 5; i++) assert.equal((await bad()).err.code, 'unauthorized')
  const locked = await bad()
  assert.equal(locked.err.code, 'locked_out')
  assert.ok(locked.err.retryAfter > 0)
  // even a correct proof is refused while locked
  const { res } = await c.authenticate(SECRET)
  assert.equal(res.err.code, 'locked_out')
})

test('status.get and the binary Status value agree', async () => {
  const { dev, client } = await paired()
  const r = await client.request('status.get')
  assert.equal(r.d.phase, 'idle')
  assert.equal(r.d.recording, false)
  assert.equal(r.d.cameraReady, true)
  assert.equal(r.d.configRev, 1)
  assert.equal(r.d.next.type, 'wake')
  assert.equal(r.d.next.attempt, 'Attempt 1')

  const b = dev.ble.status.toBinary()
  assert.equal(b.length, 12)
  assert.equal(b[0] & 0b10, 0b10) // camera ready
  assert.equal(b[1], 0) // idle
  assert.equal(b.readUInt32LE(2), 1) // configRev
  assert.equal(b.readUInt32LE(6), Math.floor(Date.parse(r.d.next.at) / 1000))
  assert.equal(b[10], 1) // next = wake
  assert.equal(b[11], 1) // attempts remaining
})

test('config.get hides the secret; config.set replaces, saves and reschedules', async () => {
  const { dev, client } = await paired()
  const got = await client.request('config.get')
  assert.equal(got.d.config.bleSecret, undefined)
  assert.equal(got.d.config.name, 'Test')

  const t1 = new Date(dev.clock.now + 7_200_000).toISOString()
  const t2 = new Date(dev.clock.now + 9_000_000).toISOString()
  const next = { ...got.d.config, startBefore: 10, attempts: [{ name: 'A', time: t1 }, { name: 'B', time: t2 }],
                 bleSecret: 'ff', deviceId: 'HACKED', configRev: 999 }
  const before = dev.timers.length
  const set = await client.request('config.set', { config: next })
  assert.equal(set.ok, true, JSON.stringify(set))
  assert.equal(set.d.configRev, 2)
  const jobs = (await client.request('jobs.get')).d
  assert.equal(jobs.total, 2)
  assert.equal(jobs.jobs[0].state, 'pending')
  assert.equal(jobs.jobs[0].recordTime, new Date(Date.parse(t1) - 10_000).toISOString())

  const saved = await dev.readConfig()
  assert.equal(saved.deviceId, 'ABC123') // device-owned fields ignored
  assert.equal(saved.bleSecret, SECRET)
  assert.equal(saved.configRev, 2)
  assert.equal(saved.attempts.length, 2)

  // old timers cancelled, 3 new per attempt
  assert.ok(dev.timers.slice(0, before).every((t) => t.cancelled))
  assert.equal(dev.timers.length - before, 6)
  assert.equal((await client.request('status.get')).d.configRev, 2)
})

test('more than the maximum attempts is rejected', async () => {
  const { dev, client } = await paired()
  const cfg = (await client.request('config.get')).d.config
  const attempts = Array.from({ length: 31 }, (_, i) => ({ name: `A${i}`, time: new Date(dev.clock.now + (i + 1) * 3_600_000).toISOString() }))
  const r = await client.request('config.set', { config: { ...cfg, attempts } })
  assert.equal(r.err.code, 'invalid_config')
  assert.equal(r.err.field, 'attempts')
})

test('config.set validation errors name the field and leave state untouched', async () => {
  const { dev, client } = await paired()
  const cfg = (await client.request('config.get')).d.config
  const r = await client.request('config.set', { config: { ...cfg, attempts: [{ name: 'x', time: new Date(dev.clock.now - 1).toISOString() }] } })
  assert.equal(r.err.code, 'invalid_config')
  assert.equal(r.err.field, 'attempts[0].time')
  assert.equal((await dev.readConfig()).attempts[0].name, 'Attempt 1')

  const soon = await client.request('config.set', { config: { ...cfg, attempts: [{ name: 'x', time: new Date(dev.clock.now + 10_000).toISOString() }] } })
  assert.equal(soon.err.code, 'invalid_config') // wake-up time already passed

  assert.equal((await client.request('config.set', {})).err.code, 'bad_request')
})

test('a full config fragments across a 23-byte MTU and still round-trips', async () => {
  const { dev, client } = await paired(undefined, { mtu: 23 })
  const attempts = Array.from({ length: 30 }, (_, i) => ({ name: `Attempt ${i + 1}`, time: new Date(dev.clock.now + (i + 1) * 3_600_000).toISOString() }))
  const cfg = (await client.request('config.get')).d.config
  const before = client.notifications.length
  const r = await client.request('config.set', { config: { ...cfg, attempts } })
  assert.equal(r.ok, true, JSON.stringify(r.err))
  assert.equal(r.d.configRev, 2)
  // jobs are paged so no response exceeds the message limit
  const seen = []
  let offset = 0
  for (;;) {
    const page = await client.request('jobs.get', { offset, limit: 50 })
    assert.equal(page.ok, true)
    seen.push(...page.d.jobs)
    offset += page.d.jobs.length
    if (!page.d.more) break
  }
  assert.equal(seen.length, 30)
  assert.ok(seen.length > 15) // would not have fit in one message
  // and the device's own config always fits back
  assert.equal((await client.request('config.get')).d.config.attempts.length, 30)
  assert.ok(client.notifications.length - before > 30) // many fragments
  assert.ok(client.notifications.every((n) => n.length <= 20))
})

test('time.set applies the clock, re-plans timers, and reports drift', async () => {
  const { dev, client } = await paired()
  const before = dev.timers.length
  const target = dev.clock.now - 418
  const r = await client.request('time.set', { now: new Date(target).toISOString() })
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.equal(r.d.offsetMs, 418)
  assert.deepEqual(dev.system.timeSet, [target])
  assert.ok(dev.timers.length > before) // rescheduled
  assert.equal((await client.request('time.set', { now: 'garbage' })).err.code, 'bad_request')
  // clock-set flag shows in Status
  assert.equal(dev.ble.status.toBinary()[0] & 0b1000, 0b1000)
})

test('manual camera control works when idle and is idempotent', async () => {
  const { dev, client } = await paired()
  assert.equal((await client.request('camera.wake')).ok, true)
  assert.equal((await client.request('camera.start')).d.recording, true)
  assert.equal((await client.request('status.get')).d.phase, 'recording')
  assert.equal((await client.request('camera.stop')).d.recording, false)
  assert.equal((await client.request('camera.sleep')).ok, true)
  assert.deepEqual(dev.scheduler.cam.calls, ['wake', 'start', 'stop', 'sleep'])
})

test('a scheduled attempt walks through its phases, emits events, and blocks changes', async () => {
  const { dev, client } = await paired()
  const [wake, start, stop] = dev.timers
  const cfg = (await client.request('config.get')).d.config

  await wake.fn()
  const evWake = await client.event('job')
  assert.equal(evWake.d.step, 'wake')
  assert.equal((await client.request('status.get')).d.phase, 'awake')

  // manual control, config and time are refused mid-attempt
  assert.equal((await client.request('camera.stop')).err.code, 'busy')
  assert.equal((await client.request('config.set', { config: cfg })).err.code, 'busy')
  assert.equal((await client.request('time.set', { now: new Date(dev.clock.now).toISOString() })).err.code, 'busy')
  assert.equal((await client.request('test.run')).err.code, 'busy')

  await start.fn()
  assert.equal((await client.request('status.get')).d.phase, 'recording')
  assert.equal(dev.scheduler.jobs[0].state, 'active')

  await stop.fn()
  assert.equal(dev.scheduler.jobs[0].state, 'complete')
  assert.equal((await client.request('status.get')).d.phase, 'idle')
  assert.deepEqual(dev.scheduler.cam.calls, ['wake', 'start', 'stop', 'sleep'])
  assert.equal((await client.request('jobs.get')).d.jobs[0].state, 'complete')
  assert.equal((await client.request('status.get')).d.next, null)
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

test('test.run acknowledges at once and streams progress', async () => {
  const { dev, client } = await paired()
  const r = await client.request('test.run')
  assert.deepEqual(r.d, { started: true })
  const done = await client.event('test').then(async () => {
    const d = await client.request('status.get')
    assert.equal(d.d.phase, 'test')
    return client.events
  })
  assert.ok(done.length >= 1)
  await new Promise((res) => {
    const check = () => (client.events.some((e) => e.ev === 'test' && e.d.step === 'done') ? res() : setTimeout(check, 50))
    check()
  })
  const steps = client.events.filter((e) => e.ev === 'test').map((e) => e.d.step)
  assert.deepEqual(steps, ['wake', 'start', 'stop', 'sleep', 'done'])
  assert.equal(client.events.find((e) => e.d?.step === 'done').d.ok, true)
  assert.equal(dev.scheduler.phase, 'idle')
})

test('logs.get pages by seq; logs.subscribe replays then streams', async () => {
  const { dev, client } = await paired()
  for (let i = 0; i < 30; i++) dev.logger.log('test', `line ${i}`)
  const first = await client.request('logs.get', { after: 0, limit: 10 })
  assert.equal(first.d.logs.length, 10)
  assert.equal(first.d.more, true)
  assert.equal(first.d.last, first.d.logs[9].seq)
  assert.deepEqual(Object.keys(first.d.logs[0]).sort(), ['msg', 'ns', 'seq', 'ts'])

  const rest = await client.request('logs.get', { after: first.d.last, limit: 100 })
  assert.equal(rest.d.more, false)
  assert.equal(rest.d.logs.at(-1).seq, dev.logger.seq)

  const last = dev.logger.seq
  dev.logger.log('test', 'missed while away')
  assert.equal((await client.request('logs.subscribe', { enabled: true, after: last })).ok, true)
  const replay = await client.event('log')
  assert.equal(replay.d.msg, 'missed while away')
  dev.logger.log('test', 'live')
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(client.events.some((e) => e.ev === 'log' && e.d.msg === 'live'))

  await client.request('logs.subscribe', { enabled: false })
  const n = client.events.length
  dev.logger.log('test', 'after unsubscribe')
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(client.events.length, n)
})

test('malformed input gets an error response, not a crash', async () => {
  const { client } = await paired()
  const errs = []
  client.waiters.push((m) => m.ok === false && m.id === null && errs.push(m.err.code))

  const raw = (s) => { const p = Buffer.from(s); const h = Buffer.alloc(3); h[0] = 0x80; h.writeUInt16BE(p.length, 1); client.writeRaw(Buffer.concat([h, p])) }
  raw('not json{')
  raw('{"op":"x"}')
  await new Promise((r) => setTimeout(r, 20))
  assert.deepEqual(errs, ['bad_request', 'bad_request'])

  // oversize declared length
  client.writeRaw(Buffer.from([0x80, 0xff, 0xff, 1, 2, 3]))
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(errs.includes('too_large'))
  // and the link still works
  assert.equal((await client.request('status.get')).ok, true)
})

test('one request at a time: a second in flight is busy', async () => {
  const { dev, client } = await paired()
  dev.controller.setTime = () => new Promise((r) => setTimeout(() => r({ offsetMs: 0 }), 50))
  const slow = client.request('time.set', { now: new Date().toISOString() })
  const second = await client.request('status.get')
  assert.equal(second.err.code, 'busy')
  assert.equal((await slow).ok, true)
})

test('a stalled reassembly times out with an error', async () => {
  const dev = await makeDevice()
  const client = new TestClient(dev.ble)
  client.link.reassemblyTimeoutMs = 30
  const errP = new Promise((res) => client.waiters.push((m) => m.ok === false && res(m)))
  client.writeRaw(Buffer.from([0x80, 0x00, 0x40, 1, 2, 3]))
  const e = await errP
  assert.equal(e.err.code, 'bad_request')
})

test('pairing flag shows in Status while the window is open', async () => {
  const dev = await makeDevice()
  assert.equal(dev.ble.status.toBinary()[0] & 0b100, 0)
  dev.ble.pairing.open()
  assert.equal(dev.ble.status.toBinary()[0] & 0b100, 0b100)
  dev.ble.pairing.close()
  assert.equal(dev.ble.status.toBinary()[0] & 0b100, 0)
})

test('attempts whose wake-up time already passed are marked missed', async () => {
  const dev = await makeDevice()
  dev.clock.now += 3_600_000 - 10_000 // 10 s before launch: wake was 25 s before
  dev.scheduler.reinitialize()
  assert.equal(dev.scheduler.jobs[0].state, 'missed')
  assert.equal(dev.scheduler.attemptsRemaining, 0)
  assert.equal(dev.scheduler.next, null)
})
