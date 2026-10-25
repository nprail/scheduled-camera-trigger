import test from 'node:test'
import assert from 'node:assert/strict'
import { fragment, Reassembler } from '../trigger/ble/framing.js'

const roundTrip = (payload, maxFragment) => {
  const r = new Reassembler()
  let out
  for (const f of fragment(payload, maxFragment)) {
    assert.ok(f.length <= maxFragment)
    const res = r.push(f)
    assert.equal(res.error, undefined)
    if (res.message) out = res.message
  }
  return out
}

test('matches the spec example: 60 bytes at MTU 23', () => {
  const payload = Buffer.alloc(60, 0x41)
  const frags = fragment(payload, 20)
  assert.deepEqual(frags.map((f) => f.length), [20, 20, 20, 6])
  assert.deepEqual([...frags[0].subarray(0, 3)], [0x80, 0x00, 0x3c])
  assert.deepEqual(frags.slice(1).map((f) => f[0]), [1, 2, 3])
})

test('round-trips at assorted sizes and MTUs', () => {
  for (const mtu of [23, 64, 185, 517]) {
    for (const n of [1, 17, 18, 19, 20, 21, 500, 4096]) {
      const payload = Buffer.from(Array.from({ length: n }, (_, i) => i % 251))
      assert.deepEqual(roundTrip(payload, mtu - 3), payload, `mtu ${mtu} n ${n}`)
    }
  }
})

test('fragment index wraps at 128', () => {
  const payload = Buffer.alloc(4096, 1)
  const frags = fragment(payload, 20) // > 128 fragments
  assert.ok(frags.length > 128)
  assert.equal(frags[128][0], 0) // wrapped continuation index
  assert.deepEqual(roundTrip(payload, 20), payload)
})

test('rejects out-of-order and orphan fragments', () => {
  const frags = fragment(Buffer.alloc(60, 1), 20)
  let r = new Reassembler()
  r.push(frags[0])
  assert.equal(r.push(frags[2]).error, 'bad_request')
  assert.equal(r.pending, false)

  r = new Reassembler()
  assert.equal(r.push(frags[1]).error, 'bad_request') // no first fragment
})

test('too-large messages are rejected and their continuations ignored', () => {
  const r = new Reassembler({ maxMessage: 100 })
  const frags = fragment(Buffer.alloc(200, 1), 20)
  assert.equal(r.push(frags[0]).error, 'too_large')
  for (const f of frags.slice(1)) assert.deepEqual(r.push(f), {})
  // and a valid message afterwards still works
  const ok = fragment(Buffer.from('hi'), 20)
  assert.equal(r.push(ok[0]).message.toString(), 'hi')
})

test('a new first fragment discards a partial message', () => {
  const r = new Reassembler()
  r.push(fragment(Buffer.alloc(60, 1), 20)[0])
  const res = r.push(fragment(Buffer.from('ok'), 20)[0])
  assert.equal(res.message.toString(), 'ok')
})

test('zero length and overruns are errors', () => {
  const r = new Reassembler()
  assert.equal(r.push(Buffer.from([0x80, 0, 0])).error, 'bad_request')
  assert.equal(r.push(Buffer.from([0x80, 0, 2, 1, 2, 3])).error, 'bad_request')
})
