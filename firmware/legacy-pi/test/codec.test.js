import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'fs/promises'
import { FILE } from './vectors.js'
import { fragment, Reassembler } from '../trigger/ble/framing.js'
import { clientProof, serverProof } from '../trigger/ble/auth.js'
import {
  decodeConfig, decodeStatus, decodeDeviceInfo, decodeRequest, decodeResponse, decodeEvent, truncateUtf8, MAX_ATTEMPTS,
} from '../trigger/ble/codec.js'

const vectors = JSON.parse(await readFile(FILE, 'utf8'))
const buf = (h) => Buffer.from(h, 'hex')

test('framing vectors: fragments reassemble to the payload', () => {
  for (const v of vectors.framing) {
    const frags = v.fragments.map(buf)
    assert.deepEqual(fragment(buf(v.payloadHex), v.maxFragment), frags, v.name)
    const r = new Reassembler()
    let message
    for (const f of frags) message = r.push(f).message ?? message
    assert.equal(message.toString('hex'), v.payloadHex, v.name)
  }
})

test('auth vectors', () => {
  const a = vectors.auth
  assert.equal(clientProof(buf(a.secretHex), buf(a.nonceHex), buf(a.clientNonceHex)).toString('hex'), a.clientProofHex)
  assert.equal(serverProof(buf(a.secretHex), buf(a.nonceHex), buf(a.clientNonceHex)).toString('hex'), a.serverProofHex)
})

test('status and device info vectors decode', () => {
  for (const v of vectors.status) assert.deepEqual(decodeStatus(buf(v.hex)), v.expected)
  for (const v of vectors.deviceInfo) assert.deepEqual(decodeDeviceInfo(buf(v.hex)), v.expected)
})

test('config vectors decode', () => {
  for (const v of vectors.config) assert.deepEqual(decodeConfig(buf(v.hex)), v.config, v.name)
  assert.equal(vectors.config.at(-1).config.attempts.length, MAX_ATTEMPTS)
  assert.ok(buf(vectors.config.at(-1).hex).length < 2048 - 3)
})

test('message vectors decode', () => {
  for (const v of vectors.messages) {
    if (v.kind === 'request') {
      assert.deepEqual(decodeRequest(buf(v.hex)), { op: v.op, id: v.id, fields: v.fields }, v.name)
    } else if (v.kind === 'response') {
      assert.deepEqual(decodeResponse(v.op, buf(v.hex)), { id: v.id, ok: true, fields: v.fields }, v.name)
    } else {
      assert.deepEqual(decodeEvent(buf(v.hex)), { ev: v.ev, fields: v.fields }, v.name)
    }
  }
})

test('error vectors decode', () => {
  for (const v of vectors.errors) {
    const r = decodeResponse('hello', buf(v.hex))
    assert.equal(r.ok, false)
    assert.equal(r.id, v.id)
    assert.equal(r.code, v.code, v.name)
    assert.deepEqual(r.detail, v.detail, v.name)
  }
})

test('a body shorter than its layout is a bad_request, trailing bytes are ignored', () => {
  assert.throws(() => decodeRequest(Buffer.from([0x05, 1, 0, 0])), { code: 'bad_request' })
  const t = decodeRequest(Buffer.concat([Buffer.from([0x05, 1]), Buffer.alloc(8), Buffer.from([9, 9])]))
  assert.deepEqual(t.fields, { nowMs: 0 })
  assert.throws(() => decodeRequest(Buffer.from([0x05])), { code: 'bad_request' })
})

test('truncateUtf8 never splits a character', () => {
  assert.equal(truncateUtf8('🚀🚀🚀', 9), '🚀🚀')
  assert.equal(truncateUtf8('abc', 10), 'abc')
})
