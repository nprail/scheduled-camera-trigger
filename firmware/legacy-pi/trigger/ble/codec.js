// Binary message codec for protocol v2 (docs/ble-protocol.md).
// Field objects use plain JS values, with binary blobs as lowercase hex strings (`...Hex`),
// so they can be written straight into protocol/test-vectors.json.
import {
  PROTOCOL_VERSION, ErrorCode, ERROR_ID, ProtocolError, PHASE, EVENT_TYPE, NO_ATTEMPT,
  JOB_STATE, JOB_STEP, TEST_STEP, CAMERA_TYPE, FW_STATE, CHIP,
} from './protocol.js'

export const OP = {
  hello: 0x01,
  auth: 0x02,
  'auth.pair': 0x03,
  'time.get': 0x04,
  'time.set': 0x05,
  'config.get': 0x07,
  'config.set': 0x08,
  'jobs.get': 0x09,
  'camera.wake': 0x10,
  'camera.start': 0x11,
  'camera.stop': 0x12,
  'camera.sleep': 0x13,
  'test.run': 0x14,
  'logs.get': 0x20,
  'logs.subscribe': 0x21,
  'fw.begin': 0x30,
  'fw.end': 0x31,
  'fw.abort': 0x32,
}
export const OP_NAME = Object.fromEntries(Object.entries(OP).map(([k, v]) => [v, k]))
export const EVENT_ID = { job: 0x01, config: 0x02, test: 0x03, log: 0x04, fw: 0x05 }
export const EVENT_NAME = Object.fromEntries(Object.entries(EVENT_ID).map(([k, v]) => [v, k]))
const ERROR_NAME = Object.fromEntries(Object.entries(ERROR_ID).map(([k, v]) => [v, k]))

const RESPONSE = 0x80
const EVENT = 0x81
const NONE = 0xff

const invert = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [v, k]))
const JOB_STATE_NAME = invert(JOB_STATE)
const JOB_STEP_NAME = invert(JOB_STEP)
const TEST_STEP_NAME = invert(TEST_STEP)
const CAMERA_NAME = invert(CAMERA_TYPE)
const FW_STATE_NAME = invert(FW_STATE)
const PHASE_NAME = invert(PHASE)
const EVENT_TYPE_NAME = invert(EVENT_TYPE)

const bad = (msg) => new ProtocolError(ErrorCode.BAD_REQUEST, msg)

/** Cut a string to at most `max` UTF-8 bytes without splitting a character. */
export const truncateUtf8 = (s, max) => {
  let out = ''
  let bytes = 0
  for (const ch of s) {
    const n = Buffer.byteLength(ch)
    if (bytes + n > max) break
    out += ch
    bytes += n
  }
  return out
}

class Writer {
  constructor() { this.parts = [] }
  u8(v) { const b = Buffer.alloc(1); b.writeUInt8(v); this.parts.push(b); return this }
  u16(v) { const b = Buffer.alloc(2); b.writeUInt16LE(v); this.parts.push(b); return this }
  u32(v) { const b = Buffer.alloc(4); b.writeUInt32LE(v >>> 0); this.parts.push(b); return this }
  i32(v) { const b = Buffer.alloc(4); b.writeInt32LE(v); this.parts.push(b); return this }
  u64(v) { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(Math.trunc(v))); this.parts.push(b); return this }
  hex(h, bytes) {
    const b = Buffer.from(h, 'hex')
    if (b.length !== bytes) throw bad(`expected ${bytes} bytes of hex`)
    this.parts.push(b)
    return this
  }
  str(s, max) {
    const b = Buffer.from(s, 'utf8')
    if (b.length > max) throw bad(`string longer than ${max} bytes`)
    this.u8(b.length)
    this.parts.push(b)
    return this
  }
  done() { return Buffer.concat(this.parts) }
}

class Reader {
  constructor(buf) { this.buf = buf; this.pos = 0 }
  need(n) {
    if (this.pos + n > this.buf.length) throw bad('message too short')
  }
  u8() { this.need(1); return this.buf.readUInt8(this.pos++) }
  u16() { this.need(2); const v = this.buf.readUInt16LE(this.pos); this.pos += 2; return v }
  u32() { this.need(4); const v = this.buf.readUInt32LE(this.pos); this.pos += 4; return v }
  i32() { this.need(4); const v = this.buf.readInt32LE(this.pos); this.pos += 4; return v }
  u64() { this.need(8); const v = Number(this.buf.readBigUInt64LE(this.pos)); this.pos += 8; return v }
  hex(n) { this.need(n); const v = this.buf.subarray(this.pos, this.pos + n).toString('hex'); this.pos += n; return v }
  str() {
    const n = this.u8()
    this.need(n)
    const v = this.buf.subarray(this.pos, this.pos + n).toString('utf8')
    this.pos += n
    return v
  }
  get left() { return this.buf.length - this.pos }
}


// --- Config ------------------------------------------------------------

export const CONFIG_LAYOUT = 1
export const MAX_ATTEMPTS = 30
export const MAX_NAME = 32

const ipToBytes = (ip) => {
  if (ip == null || ip === '') return [0, 0, 0, 0]
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip)
  if (!m) return [0, 0, 0, 0]
  const parts = m.slice(1).map(Number)
  return parts.every((p) => p <= 255) ? parts : [0, 0, 0, 0]
}

/** Wire config: { cameraType, wakeUpTimeout, startBefore, endAfter, cameraIp, name, attempts: [{ time, name }] } */
export const encodeConfig = (c) => {
  if (!(c.cameraType in CAMERA_TYPE)) throw bad('unknown cameraType')
  if ((c.attempts?.length ?? 0) > MAX_ATTEMPTS) throw bad('too many attempts')
  const w = new Writer()
  w.u8(CONFIG_LAYOUT).u8(CAMERA_TYPE[c.cameraType])
  w.u16(c.wakeUpTimeout).u16(c.startBefore).u16(c.endAfter)
  for (const b of ipToBytes(c.cameraIp)) w.u8(b)
  w.str(c.name ?? '', MAX_NAME)
  w.u8(c.attempts.length)
  for (const a of c.attempts) w.u32(a.time).str(a.name, MAX_NAME)
  return w.done()
}

export const decodeConfig = (buf) => {
  const r = new Reader(buf)
  const layout = r.u8()
  if (layout !== CONFIG_LAYOUT) throw bad(`unknown config layout ${layout}`)
  const type = r.u8()
  if (!(type in CAMERA_NAME)) throw bad('unknown cameraType')
  const config = {
    cameraType: CAMERA_NAME[type],
    wakeUpTimeout: r.u16(),
    startBefore: r.u16(),
    endAfter: r.u16(),
  }
  const ip = [r.u8(), r.u8(), r.u8(), r.u8()]
  config.cameraIp = ip.every((b) => b === 0) ? null : ip.join('.')
  config.name = r.str()
  const count = r.u8()
  if (count > MAX_ATTEMPTS) throw bad('too many attempts')
  config.attempts = []
  for (let i = 0; i < count; i++) config.attempts.push({ time: r.u32(), name: r.str() })
  return config
}

// --- Device Info and Status ----------------------------------------------

/**
 * `cams` is the camera types the device can drive, as a bit set on the wire (bit = CAMERA_TYPE value).
 * `caps`, `chip` and `slot` are optional trailing fields (firmware update support); they are written only
 * when `caps` is given, and a device without them reads back as caps 0, chip 'none', slot 0.
 */
export const encodeDeviceInfo = ({ v = PROTOCOL_VERSION, id, cam, cams = Object.keys(CAMERA_TYPE), fw, name, caps, chip = 'none', slot = 0 }) => {
  const w = new Writer()
    .u8(v)
    .hex(id.toLowerCase(), 3)
    .u8(CAMERA_TYPE[cam] ?? 0)
    .u8(cams.reduce((mask, c) => mask | (1 << CAMERA_TYPE[c]), 0))
    .str(fw, 255)
    .str(name ?? '', MAX_NAME)
  if (caps != null) w.u8(caps).u8(CHIP[chip] ?? 0).u32(slot)
  return w.done()
}

export const decodeDeviceInfo = (buf) => {
  const r = new Reader(buf)
  const v = r.u8()
  const id = r.hex(3).toUpperCase()
  const cam = CAMERA_NAME[r.u8()] ?? 'generic'
  const mask = r.u8()
  const cams = Object.keys(CAMERA_TYPE).filter((c) => mask & (1 << CAMERA_TYPE[c]))
  const info = { v, id, cam, cams, fw: r.str(), name: r.str() }
  info.caps = r.left >= 1 ? r.u8() : 0
  info.chip = invert(CHIP)[r.left >= 1 ? r.u8() : 0] ?? 'none'
  info.slot = r.left >= 4 ? r.u32() : 0
  return info
}

export const encodeStatus = ({ flags, phase, configRev, nextEventAt, nextEventType, attemptsRemaining, nextAttempt }) =>
  new Writer()
    .u8(flags & 0xff)
    .u8(PHASE[phase] ?? PHASE.error)
    .u32(configRev)
    .u32(nextEventAt)
    .u8(EVENT_TYPE[nextEventType] ?? 0)
    .u8(Math.min(attemptsRemaining, 255))
    .u8(nextAttempt == null ? NO_ATTEMPT : nextAttempt)
    .done()

export const decodeStatus = (buf) => {
  const r = new Reader(buf)
  const flags = r.u8()
  const phase = PHASE_NAME[r.u8()] ?? 'error'
  const configRev = r.u32()
  const nextEventAt = r.u32()
  const nextEventType = EVENT_TYPE_NAME[r.u8()] ?? 'none'
  const attemptsRemaining = r.u8()
  const next = r.u8()
  return { flags, phase, configRev, nextEventAt, nextEventType, attemptsRemaining, nextAttempt: next === NO_ATTEMPT ? null : next }
}

// --- Log lines -----------------------------------------------------------

const writeLine = (w, l) =>
  w.u32(l.seq).u32(l.ts).str(truncateUtf8(l.ns, 8), 8).str(truncateUtf8(l.msg, 120), 120)
const readLine = (r) => ({ seq: r.u32(), ts: r.u32(), ns: r.str(), msg: r.str() })

// --- Requests (client -> device) ------------------------------------------

const REQUEST_BODY = {
  hello: { enc: () => Buffer.alloc(0), dec: () => ({}) },
  auth: {
    enc: (f) => new Writer().hex(f.clientNonceHex, 16).hex(f.proofHex, 32).done(),
    dec: (r) => ({ clientNonceHex: r.hex(16), proofHex: r.hex(32) }),
  },
  'auth.pair': {
    enc: (f) => new Writer().hex(f.secretHex, 16).done(),
    dec: (r) => ({ secretHex: r.hex(16) }),
  },
  'time.get': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'time.set': {
    enc: (f) => new Writer().u64(f.nowMs).done(),
    dec: (r) => ({ nowMs: r.u64() }),
  },
  'config.get': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'config.set': {
    enc: (f) => encodeConfig(f.config),
    dec: (r) => ({ config: decodeConfig(r.buf.subarray(r.pos)) }),
  },
  'jobs.get': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'camera.wake': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'camera.start': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'camera.stop': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'camera.sleep': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'test.run': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'logs.get': {
    enc: (f) => new Writer().u32(f.after).u8(f.limit).done(),
    dec: (r) => ({ after: r.u32(), limit: r.u8() }),
  },
  'logs.subscribe': {
    enc: (f) => new Writer().u8(f.enabled ? 1 : 0).u32(f.after).done(),
    dec: (r) => ({ enabled: r.u8() !== 0, after: r.u32() }),
  },
  'fw.begin': {
    enc: (f) => new Writer().u32(f.size).hex(f.sha256Hex, 32).hex(f.sigHex, 64).done(),
    dec: (r) => ({ size: r.u32(), sha256Hex: r.hex(32), sigHex: r.hex(64) }),
  },
  'fw.end': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'fw.abort': { enc: () => Buffer.alloc(0), dec: () => ({}) },
}

export const encodeRequest = (op, id, fields = {}) =>
  Buffer.concat([Buffer.from([OP[op], id & 0xff]), REQUEST_BODY[op].enc(fields)])

/** Returns { op, id, fields } or throws a ProtocolError. An unknown op code yields { op: null, id, code }. */
export const decodeRequest = (buf) => {
  if (buf.length < 2) throw bad('message too short')
  const code = buf[0]
  const id = buf[1]
  const op = OP_NAME[code]
  if (!op) return { op: null, id, code }
  const r = new Reader(buf.subarray(2))
  return { op, id, fields: REQUEST_BODY[op].dec(r) }
}

// --- Responses (device -> client) -----------------------------------------

const RESPONSE_BODY = {
  hello: {
    enc: (f) => new Writer().u8(f.v).u8(f.paired ? 1 : 0).hex(f.nonceHex, 16).u64(f.nowMs).u16(f.maxMessage).done(),
    dec: (r) => ({ v: r.u8(), paired: (r.u8() & 1) !== 0, nonceHex: r.hex(16), nowMs: r.u64(), maxMessage: r.u16() }),
  },
  auth: {
    enc: (f) => new Writer().hex(f.proofHex, 32).done(),
    dec: (r) => ({ proofHex: r.hex(32) }),
  },
  'auth.pair': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'time.get': {
    enc: (f) => new Writer().u64(f.nowMs).done(),
    dec: (r) => ({ nowMs: r.u64() }),
  },
  'time.set': {
    enc: (f) => new Writer().u64(f.nowMs).i32(Math.max(-2147483648, Math.min(2147483647, f.offsetMs))).done(),
    dec: (r) => ({ nowMs: r.u64(), offsetMs: r.i32() }),
  },
  'config.get': {
    enc: (f) => encodeConfig(f.config),
    dec: (r) => ({ config: decodeConfig(r.buf.subarray(r.pos)) }),
  },
  'config.set': {
    enc: (f) => new Writer().u32(f.configRev).done(),
    dec: (r) => ({ configRev: r.u32() }),
  },
  'jobs.get': {
    enc: (f) => {
      const w = new Writer().u8(f.states.length)
      for (const s of f.states) w.u8(JOB_STATE[s])
      return w.done()
    },
    dec: (r) => {
      const n = r.u8()
      const states = []
      for (let i = 0; i < n; i++) states.push(JOB_STATE_NAME[r.u8()] ?? 'pending')
      return { states }
    },
  },
  'test.run': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'logs.get': {
    enc: (f) => {
      const w = new Writer().u32(f.last).u8(f.more ? 1 : 0).u8(f.lines.length)
      for (const l of f.lines) writeLine(w, l)
      return w.done()
    },
    dec: (r) => {
      const last = r.u32()
      const more = r.u8() !== 0
      const n = r.u8()
      const lines = []
      for (let i = 0; i < n; i++) lines.push(readLine(r))
      return { last, more, lines }
    },
  },
  'logs.subscribe': { enc: () => Buffer.alloc(0), dec: () => ({}) },
  'fw.begin': {
    enc: (f) => new Writer().u32(f.offset).u16(f.window).done(),
    dec: (r) => ({ offset: r.u32(), window: r.u16() }),
  },
  'fw.end': {
    enc: (f) => new Writer().u16(f.rebootInMs).done(),
    dec: (r) => ({ rebootInMs: r.u16() }),
  },
  'fw.abort': { enc: () => Buffer.alloc(0), dec: () => ({}) },
}
for (const name of ['camera.wake', 'camera.start', 'camera.stop', 'camera.sleep']) {
  RESPONSE_BODY[name] = {
    enc: (f) => new Writer().u8(f.recording ? 1 : 0).done(),
    dec: (r) => ({ recording: r.u8() !== 0 }),
  }
}

export const encodeResponse = (op, id, fields = {}) =>
  Buffer.concat([Buffer.from([RESPONSE, id & 0xff, 0]), RESPONSE_BODY[op].enc(fields)])

/** Error detail fields: { retryAfter } | { field, index, reason } | { message }. */
export const encodeError = (id, code, detail = {}) => {
  const head = Buffer.from([RESPONSE, id & 0xff, ERROR_ID[code]])
  const w = new Writer()
  if (code === ErrorCode.LOCKED_OUT) w.u16(detail.retryAfter ?? 0)
  else if (code === ErrorCode.INVALID_CONFIG) w.u8(detail.field ?? 0).u8(detail.index ?? 0).u8(detail.reason ?? 0)
  else if (code === ErrorCode.INTERNAL && detail.message) w.str(truncateUtf8(detail.message, 120), 120)
  else if (code === ErrorCode.BAD_IMAGE) w.u8(detail.reason ?? 0)
  return Buffer.concat([head, w.done()])
}

/** Decode a response. `op` selects the body layout for a success. Returns { id, ok: true, fields } or { id, ok: false, code, detail }. */
export const decodeResponse = (op, buf) => {
  if (buf.length < 3 || buf[0] !== RESPONSE) throw bad('not a response')
  const id = buf[1]
  const status = buf[2]
  const r = new Reader(buf.subarray(3))
  if (status === 0) return { id, ok: true, fields: RESPONSE_BODY[op].dec(r) }
  const code = ERROR_NAME[status] ?? ErrorCode.INTERNAL
  const detail = {}
  if (code === ErrorCode.LOCKED_OUT) detail.retryAfter = r.left >= 2 ? r.u16() : 0
  else if (code === ErrorCode.INVALID_CONFIG) {
    if (r.left >= 3) { detail.field = r.u8(); detail.index = r.u8(); detail.reason = r.u8() }
  } else if (code === ErrorCode.INTERNAL && r.left >= 1) detail.message = r.str()
  else if (code === ErrorCode.BAD_IMAGE) detail.reason = r.left >= 1 ? r.u8() : 0
  return { id, ok: false, code, detail }
}

// --- Events ---------------------------------------------------------------

const EVENT_BODY = {
  job: {
    enc: (f) => new Writer().u8(f.attempt).u8(JOB_STEP[f.step]).u32(f.at).done(),
    dec: (r) => ({ attempt: r.u8(), step: JOB_STEP_NAME[r.u8()] ?? 'wake', at: r.u32() }),
  },
  config: {
    enc: (f) => new Writer().u32(f.configRev).done(),
    dec: (r) => ({ configRev: r.u32() }),
  },
  test: {
    enc: (f) => new Writer().u8(TEST_STEP[f.step]).u8(f.ok ? 1 : 0).str(truncateUtf8(f.error ?? '', 120), 120).done(),
    dec: (r) => ({ step: TEST_STEP_NAME[r.u8()] ?? 'wake', ok: r.u8() !== 0, error: r.left ? r.str() : '' }),
  },
  log: {
    enc: (f) => writeLine(new Writer(), f).done(),
    dec: (r) => readLine(r),
  },
  fw: {
    enc: (f) => new Writer().u8(FW_STATE[f.state]).u32(f.offset).u8(f.reason ?? 0).done(),
    dec: (r) => ({ state: FW_STATE_NAME[r.u8()] ?? 'failed', offset: r.u32(), reason: r.left ? r.u8() : 0 }),
  },
}

export const encodeEvent = (ev, fields) =>
  Buffer.concat([Buffer.from([EVENT, EVENT_ID[ev]]), EVENT_BODY[ev].enc(fields)])

export const decodeEvent = (buf) => {
  if (buf.length < 2 || buf[0] !== EVENT) throw bad('not an event')
  const ev = EVENT_NAME[buf[1]]
  if (!ev) return { ev: null, code: buf[1] }
  return { ev, fields: EVENT_BODY[ev].dec(new Reader(buf.subarray(2))) }
}

/** True for a response/event discriminator byte. */
export const isResponse = (buf) => buf.length > 0 && buf[0] === RESPONSE
export const isEvent = (buf) => buf.length > 0 && buf[0] === EVENT

// --- Firmware data writes ---------------------------------------------------

/** One write to the Firmware characteristic: the offset of the first byte, then the bytes. */
export const encodeFirmwareWrite = (offset, data) => Buffer.concat([new Writer().u32(offset).done(), data])
export const decodeFirmwareWrite = (buf) => {
  const r = new Reader(buf)
  const offset = r.u32()
  return { offset, data: buf.subarray(4) }
}
