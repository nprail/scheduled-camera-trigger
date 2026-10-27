// Cross-implementation test vectors (protocol/test-vectors.json).
// The Node implementation is the reference; the ESP32 firmware and both apps read the
// same file. Regenerate with: node test/vectors.js --write
import { writeFile } from 'fs/promises'
import { fileURLToPath } from 'url'
import { fragment } from '../trigger/ble/framing.js'
import { clientProof, serverProof } from '../trigger/ble/auth.js'
import { Flag, ErrorCode, FIELD, REASON, BAD_IMAGE, CAP_FIRMWARE_UPDATE } from '../trigger/ble/protocol.js'
import {
  encodeConfig, encodeStatus, encodeDeviceInfo, encodeRequest, encodeResponse, encodeError, encodeEvent, encodeFirmwareWrite,
} from '../trigger/ble/codec.js'
import { parseImage, compareVersions, evaluateManifest } from '../trigger/ble/firmware-image.js'

export const FILE = fileURLToPath(new URL('../../../protocol/test-vectors.json', import.meta.url))

const hex = (b) => b.toString('hex')
const bytes = (start, n) => Buffer.from(Array.from({ length: n }, (_, i) => (start + i) & 0xff))

const configs = () => {
  const generic = {
    cameraType: 'generic', wakeUpTimeout: 5, startBefore: 20, endAfter: 30,
    cameraIp: null, name: 'Pad Cam',
    attempts: [
      { time: 1793554740, name: 'Attempt 1' },
      { time: 1793641140, name: 'Attempt 2' },
    ],
  }
  const zcam = {
    cameraType: 'zcam', wakeUpTimeout: 600, startBefore: 3600, endAfter: 0,
    cameraIp: '192.168.1.50', name: '',
    attempts: [],
  }
  const unicode = {
    cameraType: 'generic', wakeUpTimeout: 0, startBefore: 1, endAfter: 2,
    cameraIp: null, name: 'Café 🚀',
    attempts: [{ time: 4294967295, name: 'Lançamento' }],
  }
  const full = {
    cameraType: 'zcam', wakeUpTimeout: 5, startBefore: 20, endAfter: 30,
    cameraIp: '10.0.0.7', name: 'x'.repeat(32),
    attempts: Array.from({ length: 30 }, (_, i) => ({ time: 1793554740 + i * 3600, name: `Attempt ${i + 1}`.padEnd(32, '.') })),
  }
  return [
    { name: 'generic camera with two attempts', config: generic },
    { name: 'zcam with no attempts and an empty name', config: zcam },
    { name: 'unicode names and the largest time', config: unicode },
    { name: 'the largest config: 30 attempts with 32-byte names', config: full },
  ].map((c) => ({ ...c, hex: hex(encodeConfig(c.config)) }))
}

// --- Firmware files and manifests (phone side) -----------------------------

/** A synthetic ESP image: 24-byte header, an 8-byte segment header, the app descriptor magic, then filler and an optional tag. */
const image = ({ chipId, magic = 0xe9, appDesc = true, tag, size = 256 }) => {
  const b = Buffer.alloc(size, 0x55)
  b.fill(0, 0, 64)
  b.writeUInt8(magic, 0)
  b.writeUInt16LE(chipId, 12)
  if (appDesc) b.writeUInt32LE(0xabcd5432, 32)
  if (tag) {
    const t = Buffer.alloc(50)
    t.write('KAIROSFW', 0, 'ascii')
    t.writeUInt8(tag.version ?? 1, 8)
    t.writeUInt8(tag.chip, 9)
    t.write(tag.fw, 10, 'ascii')
    t.copy(b, 128)
  }
  return b
}

const firmwareVectors = () => {
  const S3 = 9
  const C3 = 5
  const images = [
    { name: 'app image for the S3, with a tag', chip: 'esp32s3', buf: image({ chipId: S3, tag: { chip: 1, fw: '1.3.0' } }) },
    { name: 'app image for the C3, with a tag and a pre-release version', chip: 'esp32c3', buf: image({ chipId: C3, tag: { chip: 2, fw: '1.3.0-beta.2' } }) },
    { name: 'app image without a tag', chip: 'esp32s3', buf: image({ chipId: S3 }) },
    { name: 'an S3 image offered to a C3', chip: 'esp32c3', buf: image({ chipId: S3, tag: { chip: 1, fw: '1.3.0' } }) },
    { name: 'the header says S3 but the tag says C3', chip: 'esp32s3', buf: image({ chipId: S3, tag: { chip: 2, fw: '1.3.0' } }) },
    { name: 'a factory image (no application descriptor)', chip: 'esp32s3', buf: image({ chipId: S3, appDesc: false }) },
    { name: 'not an image', chip: 'esp32s3', buf: image({ chipId: S3, magic: 0x7f }) },
    { name: 'too short', chip: 'esp32s3', buf: Buffer.alloc(63, 0xe9) },
    { name: 'an unknown chip', chip: 'esp32s3', buf: image({ chipId: 2 }) },
    { name: 'a tag with an unknown tag version is ignored', chip: 'esp32s3', buf: image({ chipId: S3, tag: { version: 2, chip: 1, fw: '9.9.9' } }) },
  ].map(({ buf, ...c }) => ({ ...c, hex: hex(buf), expected: parseImage(buf, c.chip) }))

  const pairs = [
    ['1.2.0', '1.2.0'], ['1.2.0', '1.10.0'], ['2.0.0', '1.99.99'], ['1.0.0', '1.0.0-beta.1'], ['1.0.0-beta.1', '1.0.0-beta.2'],
    ['1.0.0-beta.2', '1.0.0-beta.10'], ['1.0.0-alpha', '1.0.0-beta'], ['1.0.0-1', '1.0.0-alpha'], ['1.0.0-alpha', '1.0.0-alpha.1'],
    ['v1.3.0', '1.3.0'], ['1.3.0+build5', '1.3.0'], ['dev', '1.0.0'], ['1.0.0', 'dev'], ['1.2', '1.2.0'], ['', '1.0.0'],
  ].map(([a, b]) => ({ a, b, expected: compareVersions(a, b) }))

  const sha = 'a'.repeat(64)
  const sig = 'b'.repeat(128)
  const img = (file, size = 1187344) => ({ file, size, sha256: sha, sig })
  const base = {
    version: '1.3.0', protocol: 2, minFrom: '1.2.0', notes: 'Fixes',
    images: { esp32s3: img('kairos-esp32s3-1.3.0-app.bin'), esp32c3: img('kairos-esp32c3-1.3.0-app.bin', 1160192) },
  }
  const only = (chip) => ({ ...base, images: { [chip]: base.images[chip] } })
  const manifests = [
    { name: 'an update is available', manifest: base, chip: 'esp32s3', deviceFw: '1.2.0' },
    { name: 'the same version', manifest: base, chip: 'esp32s3', deviceFw: '1.3.0' },
    { name: 'the device is ahead', manifest: base, chip: 'esp32s3', deviceFw: '1.4.0' },
    { name: 'a development build', manifest: base, chip: 'esp32s3', deviceFw: 'dev' },
    { name: 'a git describe build', manifest: base, chip: 'esp32s3', deviceFw: '1.2.0-4-gabcdef0-dirty' },
    { name: 'the device is too old for a Bluetooth update', manifest: base, chip: 'esp32s3', deviceFw: '1.1.0' },
    { name: 'no minFrom', manifest: { ...base, minFrom: undefined }, chip: 'esp32c3', deviceFw: '0.1.0' },
    { name: 'the image needs a newer app', manifest: { ...base, protocol: 3 }, chip: 'esp32s3', deviceFw: '1.2.0' },
    { name: 'no image for this chip', manifest: only('esp32s3'), chip: 'esp32c3', deviceFw: '1.2.0' },
    { name: 'a bad hash', manifest: { ...base, images: { esp32s3: { ...img('x.bin'), sha256: 'zz' } } }, chip: 'esp32s3', deviceFw: '1.2.0' },
    { name: 'a missing signature', manifest: { ...base, images: { esp32s3: { file: 'x.bin', size: 5, sha256: sha } } }, chip: 'esp32s3', deviceFw: '1.2.0' },
    { name: 'a bad version', manifest: { ...base, version: 'latest' }, chip: 'esp32s3', deviceFw: '1.2.0' },
    { name: 'a pre-release is newer than the previous release', manifest: { ...base, version: '1.3.0-beta.1' }, chip: 'esp32s3', deviceFw: '1.2.0' },
  ].map((m) => {
    const manifest = JSON.parse(JSON.stringify(m.manifest))
    return { ...m, manifest, appProtocol: 2, expected: evaluateManifest(manifest, { chip: m.chip, deviceFw: m.deviceFw, appProtocol: 2 }) }
  })

  return { images, versions: pairs, manifests }
}

export const build = () => {
  const cfgs = configs()
  const [generic, zcam] = cfgs.map((c) => c.config)

  const framing = [
    { name: 'tiny', payloadHex: hex(Buffer.from('hi')), maxFragment: 20 },
    { name: 'spec example, 60 bytes at MTU 23', payloadHex: hex(Buffer.alloc(60, 0x41)), maxFragment: 20 },
    {
      name: 'a config.set request spanning several fragments',
      payloadHex: hex(encodeRequest('config.set', 7, { config: generic })),
      maxFragment: 40,
    },
    { name: 'exactly one fragment', payloadHex: hex(Buffer.alloc(17, 0x42)), maxFragment: 20 },
    { name: 'one byte over one fragment', payloadHex: hex(Buffer.alloc(18, 0x43)), maxFragment: 20 },
    { name: 'the largest config at MTU 23', payloadHex: hex(encodeRequest('config.set', 255, { config: cfgs[3].config })), maxFragment: 20 },
  ].map((c) => ({
    ...c,
    fragments: fragment(Buffer.from(c.payloadHex, 'hex'), c.maxFragment).map(hex),
  }))

  const secret = Buffer.from('00112233445566778899aabbccddeeff', 'hex')
  const nonce = bytes(0, 16)
  const clientNonce = bytes(16, 16)
  const auth = {
    secretHex: hex(secret),
    nonceHex: hex(nonce),
    clientNonceHex: hex(clientNonce),
    clientProofHex: hex(clientProof(secret, nonce, clientNonce)),
    serverProofHex: hex(serverProof(secret, nonce, clientNonce)),
  }

  const statuses = [
    { flags: Flag.CAMERA_READY | Flag.CLOCK_VALID, phase: 'idle', configRev: 1, nextEventAt: 1793554715, nextEventType: 'wake', attemptsRemaining: 1, nextAttempt: 0 },
    { flags: Flag.RECORDING | Flag.CAMERA_READY | Flag.CLOCK_VALID | Flag.PAIRING_OPEN, phase: 'recording', configRev: 305419896, nextEventAt: 0, nextEventType: 'none', attemptsRemaining: 255, nextAttempt: null },
    { flags: 0, phase: 'error', configRev: 0, nextEventAt: 4294967295, nextEventType: 'stop', attemptsRemaining: 30, nextAttempt: 29 },
  ].map((s) => ({ hex: hex(encodeStatus(s)), expected: s }))

  const deviceInfo = [
    { v: 2, id: 'A1B2C3', cam: 'generic', cams: ['generic', 'zcam'], fw: '1.0.0', name: 'Pad Cam' },
    { v: 2, id: '00FF10', cam: 'zcam', cams: ['generic', 'zcam'], fw: '2.3.4-beta', name: '' },
    { v: 2, id: '0A0B0C', cam: 'generic', cams: ['generic'], fw: '1.0.0', name: 'No Ethernet' },
    // the optional trailing fields: firmware update support, the chip and the largest image
    { v: 2, id: 'A1B2C3', cam: 'generic', cams: ['generic', 'zcam'], fw: '1.2.0', name: 'Pad Cam', caps: CAP_FIRMWARE_UPDATE, chip: 'esp32s3', slot: 0x1f0000 },
    { v: 2, id: '00FF10', cam: 'generic', cams: ['generic'], fw: '1.2.0-beta.1', name: '', caps: CAP_FIRMWARE_UPDATE, chip: 'esp32c3', slot: 0x1d0000 },
    { v: 2, id: '0A0B0C', cam: 'generic', cams: ['generic'], fw: '1.0.0', name: '', caps: 0, chip: 'none', slot: 0 },
  ].map((d) => ({ hex: hex(encodeDeviceInfo(d)), trailing: d.caps != null, expected: { caps: 0, chip: 'none', slot: 0, ...d } }))

  const req = (name, op, id, fields = {}) => ({ name, kind: 'request', op, id, fields, hex: hex(encodeRequest(op, id, fields)) })
  const res = (name, op, id, fields = {}) => ({ name, kind: 'response', op, id, fields, hex: hex(encodeResponse(op, id, fields)) })
  const ev = (name, event, fields) => ({ name, kind: 'event', ev: event, fields, hex: hex(encodeEvent(event, fields)) })
  const line = { seq: 121, ts: 1793554715, ns: 'INDEX', msg: "Start 'Attempt 1'" }

  const messages = [
    req('hello', 'hello', 1),
    res('hello', 'hello', 1, { v: 2, paired: true, nonceHex: hex(nonce), nowMs: 1793554715482, maxMessage: 2048 }),
    res('hello, unpaired', 'hello', 2, { v: 2, paired: false, nonceHex: hex(clientNonce), nowMs: 0, maxMessage: 2048 }),
    req('auth', 'auth', 2, { clientNonceHex: auth.clientNonceHex, proofHex: auth.clientProofHex }),
    res('auth', 'auth', 2, { proofHex: auth.serverProofHex }),
    req('auth.pair', 'auth.pair', 3, { secretHex: auth.secretHex }),
    res('auth.pair', 'auth.pair', 3),
    req('time.get', 'time.get', 4),
    res('time.get', 'time.get', 4, { nowMs: 1793554715482 }),
    req('time.set', 'time.set', 5, { nowMs: 1793554715900 }),
    res('time.set, device was behind', 'time.set', 5, { nowMs: 1793554715901, offsetMs: -418 }),
    res('time.set, device was ahead', 'time.set', 6, { nowMs: 1793554715901, offsetMs: 2000 }),
    req('config.get', 'config.get', 7),
    res('config.get', 'config.get', 7, { config: generic }),
    req('config.set', 'config.set', 8, { config: zcam }),
    res('config.set', 'config.set', 8, { configRev: 13 }),
    req('jobs.get', 'jobs.get', 9),
    res('jobs.get', 'jobs.get', 9, { states: ['pending', 'active', 'complete', 'missed'] }),
    res('jobs.get, none', 'jobs.get', 10, { states: [] }),
    req('camera.wake', 'camera.wake', 11),
    req('camera.start', 'camera.start', 12),
    req('camera.stop', 'camera.stop', 13),
    req('camera.sleep', 'camera.sleep', 14),
    res('camera.start', 'camera.start', 12, { recording: true }),
    res('camera.stop', 'camera.stop', 13, { recording: false }),
    req('test.run', 'test.run', 15),
    res('test.run', 'test.run', 15),
    req('logs.get', 'logs.get', 16, { after: 120, limit: 20 }),
    res('logs.get', 'logs.get', 16, { last: 121, more: true, lines: [line, { seq: 122, ts: 1793554716, ns: 'BLE', msg: 'Connected' }] }),
    res('logs.get, empty', 'logs.get', 17, { last: 140, more: false, lines: [] }),
    req('logs.subscribe', 'logs.subscribe', 18, { enabled: true, after: 140 }),
    req('logs.unsubscribe', 'logs.subscribe', 19, { enabled: false, after: 0 }),
    res('logs.subscribe', 'logs.subscribe', 18),
    ev('job wake', 'job', { attempt: 0, step: 'wake', at: 1793554715 }),
    ev('job complete', 'job', { attempt: 29, step: 'complete', at: 1793554800 }),
    ev('job missed', 'job', { attempt: 3, step: 'missed', at: 1793554700 }),
    ev('config', 'config', { configRev: 14 }),
    ev('test step', 'test', { step: 'start', ok: false, error: '' }),
    ev('test done', 'test', { step: 'done', ok: true, error: '' }),
    ev('test failed', 'test', { step: 'done', ok: false, error: 'ZCam failed to connect' }),
    ev('log', 'log', line),
    req('fw.begin', 'fw.begin', 20, { size: 1187344, sha256Hex: hex(bytes(0, 32)), sigHex: hex(bytes(32, 64)) }),
    req('fw.begin, unsigned', 'fw.begin', 21, { size: 65536, sha256Hex: hex(bytes(7, 32)), sigHex: '00'.repeat(64) }),
    res('fw.begin', 'fw.begin', 20, { offset: 0, window: 8192 }),
    res('fw.begin, resuming', 'fw.begin', 22, { offset: 524288, window: 8192 }),
    req('fw.end', 'fw.end', 23),
    res('fw.end', 'fw.end', 23, { rebootInMs: 500 }),
    req('fw.abort', 'fw.abort', 24),
    res('fw.abort', 'fw.abort', 24),
    ev('fw receiving', 'fw', { state: 'receiving', offset: 2048, reason: 0 }),
    ev('fw verifying', 'fw', { state: 'verifying', offset: 1187344, reason: 0 }),
    ev('fw ready', 'fw', { state: 'ready', offset: 1187344, reason: 0 }),
    ev('fw failed, hash mismatch', 'fw', { state: 'failed', offset: 1187344, reason: BAD_IMAGE.hash }),
  ]

  const err = (name, id, code, detail = {}) => ({ name, id, code, detail, hex: hex(encodeError(id, code, detail)) })
  const errors = [
    err('bad_request', 0, ErrorCode.BAD_REQUEST),
    err('unauthorized', 5, ErrorCode.UNAUTHORIZED),
    err('locked_out', 6, ErrorCode.LOCKED_OUT, { retryAfter: 300 }),
    err('not_supported', 7, ErrorCode.NOT_SUPPORTED),
    err('busy', 8, ErrorCode.BUSY),
    err('invalid_config, an attempt time in the past', 9, ErrorCode.INVALID_CONFIG, { field: FIELD.attemptTime, index: 2, reason: REASON.past }),
    err('invalid_config, camera ip required', 10, ErrorCode.INVALID_CONFIG, { field: FIELD.cameraIp, index: 0, reason: REASON.required }),
    err('invalid_config, too many attempts', 11, ErrorCode.INVALID_CONFIG, { field: FIELD.attempts, index: 0, reason: REASON.too_many }),
    err('too_large', 12, ErrorCode.TOO_LARGE),
    err('pairing_closed', 13, ErrorCode.PAIRING_CLOSED),
    err('internal with detail', 14, ErrorCode.INTERNAL, { message: 'Camera is not available' }),
    err('internal without detail', 15, ErrorCode.INTERNAL),
    err('bad_image, chip', 16, ErrorCode.BAD_IMAGE, { reason: BAD_IMAGE.chip }),
    err('bad_image, signature invalid', 17, ErrorCode.BAD_IMAGE, { reason: BAD_IMAGE.signature_invalid }),
    err('bad_image, interrupted', 18, ErrorCode.BAD_IMAGE, { reason: BAD_IMAGE.interrupted }),
  ]

  const firmwareWrites = [
    { name: 'first chunk', offset: 0, dataHex: hex(bytes(0, 16)) },
    { name: 'a later chunk', offset: 1187328, dataHex: hex(bytes(100, 240)) },
    { name: 'the largest offset', offset: 4294967295, dataHex: 'ff' },
  ].map((w) => ({ ...w, hex: hex(encodeFirmwareWrite(w.offset, Buffer.from(w.dataHex, 'hex'))) }))

  const firmware = firmwareVectors()

  return {
    _comment: 'Generated by test/vectors.js; do not edit by hand. Binary values are lowercase hex.',
    framing,
    auth,
    status: statuses,
    deviceInfo,
    config: cfgs,
    messages,
    errors,
    firmwareWrites,
    firmware,
  }
}

if (process.argv.includes('--write')) {
  await writeFile(FILE, JSON.stringify(build(), null, 2) + '\n')
  console.log('wrote', FILE)
}
