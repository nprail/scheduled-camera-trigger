import { mkdtemp, writeFile, readFile } from 'fs/promises'
import { tmpdir } from 'os'
import { join } from 'path'
import { createHmac, randomBytes } from 'crypto'

import { Logger } from '../trigger/lib/logger.js'
import { Scheduler } from '../trigger/lib/scheduler.js'
import { initController } from '../trigger/controller.js'
import { createBle } from '../trigger/ble/index.js'
import { BaseCamera } from '../trigger/cameras/base.js'
import { fragment, Reassembler } from '../trigger/ble/framing.js'
import { encodeRequest, decodeResponse, decodeEvent, isEvent } from '../trigger/ble/codec.js'

export class FakeCamera extends BaseCamera {
  calls = []
  async wake() { this.calls.push('wake') }
  async start() { this.calls.push('start'); this.recording = true }
  async stop() { this.calls.push('stop'); this.recording = false }
  async sleep() { this.calls.push('sleep') }
}

export class FakeSystem {
  clockSetSinceBoot = false
  timeSet = []
  async setTime(ms) { this.timeSet.push(ms); this.clockSetSinceBoot = true }
}

export const baseConfig = (now, overrides = {}) => ({
  name: 'Test',
  deviceId: 'ABC123',
  configRev: 1,
  cameraType: 'generic',
  wakeUpTimeout: 5,
  startBefore: 20,
  endAfter: 30,
  logFile: 'log.txt',
  generic: { releaseGpioPin: 25 },
  zcam: {},
  attempts: [
    { name: 'Attempt 1', time: new Date(now + 3_600_000).toISOString() },
  ],
  ...overrides,
})

/** Real scheduler/controller/dispatcher with fake camera, clock and system. */
export const makeDevice = async (overrides = {}) => {
  const clock = { now: Date.parse('2026-11-01T12:00:00.000Z') }
  const dir = await mkdtemp(join(tmpdir(), 'cam-'))
  const configFile = join(dir, 'config.json')
  const config = baseConfig(clock.now, overrides)
  config.deviceName = `${config.name}-${config.deviceId}`
  await writeFile(configFile, JSON.stringify(config))

  const logger = new Logger({ logFile: join(dir, 'log.txt') })
  logger.log = ((orig) => (ns, msg, data) => {
    // keep test output quiet
    const realLog = console.log
    console.log = () => {}
    try { orig.call(logger, ns, msg, data) } finally { console.log = realLog }
  })(logger.log)

  const timers = [] // fake node-schedule
  const scheduleJob = (date, fn) => {
    const job = { date, fn, cancelled: false, cancel() { this.cancelled = true } }
    timers.push(job)
    return job
  }
  const system = new FakeSystem()
  const scheduler = new Scheduler({
    logger,
    cameras: { generic: FakeCamera, zcam: FakeCamera },
    scheduleJob,
    now: () => clock.now,
  })
  scheduler.initialize(config)
  const controller = initController({
    configFile, scheduler, logger, system, now: () => clock.now,
  })
  const ble = createBle({ scheduler, controller, logger, system })

  return { clock, configFile, logger, scheduler, controller, ble, system, timers,
           readConfig: async () => JSON.parse(await readFile(configFile, 'utf8')) }
}

/** A central talking to a Link over the real framing and binary codec, with a chosen MTU. */
export class TestClient {
  constructor(ble, { mtu = 23 } = {}) {
    this.mtu = mtu
    this.notifications = []
    this.events = []
    this.waiters = []
    this.reasm = new Reassembler()
    this.nextId = 1
    this.subscribed = true
    this.ops = new Map() // request id -> op, to pick the response layout

    this.link = ble.makeLink({
      notify: (frag, done) => {
        if (!this.subscribed) return false
        this.notifications.push(frag)
        queueMicrotask(() => { this.#receive(frag); done() })
        return true
      },
      maxFragment: () => this.mtu - 3,
    })
    ble.links.add(this.link)
  }

  #receive(frag) {
    const { message, error } = this.reasm.push(frag)
    if (error) throw new Error(`client framing error ${error}`)
    if (!message) return
    let msg
    if (isEvent(message)) {
      msg = decodeEvent(message)
      this.events.push(msg)
    } else {
      const id = message[1]
      msg = decodeResponse(this.ops.get(id) ?? 'hello', message)
    }
    this.waiters.slice().forEach((w) => w(msg))
  }

  #waitFor(pred, ms = 2000) {
    const hit = [...this.events].find(pred)
    if (hit) return Promise.resolve(hit)
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('timeout waiting for message')), ms)
      const w = (m) => { if (pred(m)) { clearTimeout(t); this.waiters.splice(this.waiters.indexOf(w), 1); resolve(m) } }
      this.waiters.push(w)
    })
  }

  /** Send raw framed bytes (for malformed-input tests). */
  writeRaw(buf) { this.link.onWrite(buf) }

  /** Send an already-built message body through the real framing. */
  send(buf) {
    for (const f of fragment(buf, this.mtu - 3)) this.link.onWrite(f)
  }

  /** Send a request, resolve with the decoded response: { id, ok: true, fields } or { id, ok: false, code, detail }. */
  request(op, fields = {}, id = this.nextId++) {
    this.ops.set(id, op)
    const p = this.#waitFor((m) => m.id === id)
    this.send(encodeRequest(op, id, fields))
    return p
  }

  /** Send a raw request message and resolve with the response to `id`. */
  requestRaw(buf, id, op = 'hello') {
    this.ops.set(id, op)
    const p = this.#waitFor((m) => m.id === id)
    this.send(buf)
    return p
  }

  event(name) { return this.#waitFor((m) => m.ev === name) }

  /** hello + auth with a known secret (hex). */
  async authenticate(secretHex) {
    const hello = await this.request('hello')
    const nonce = Buffer.from(hello.fields.nonceHex, 'hex')
    const clientNonce = randomBytes(16)
    const key = Buffer.from(secretHex, 'hex')
    const proof = createHmac('sha256', key).update('kairos-auth-v2').update(nonce).update(clientNonce).digest()
    const res = await this.request('auth', { clientNonceHex: clientNonce.toString('hex'), proofHex: proof.toString('hex') })
    return { hello, res, nonce, clientNonce, key }
  }
}

export const SECRET = '00112233445566778899aabbccddeeff'
