import { MAX_MESSAGE } from './protocol.js'

const FIRST = 0x80

/**
 * Split a message into fragments that each fit one ATT payload.
 * @param {Buffer} payload
 * @param {number} maxFragment ATT payload size (MTU - 3), at least 4
 * @returns {Buffer[]}
 */
export const fragment = (payload, maxFragment) => {
  if (maxFragment < 4) throw new RangeError('maxFragment too small')
  if (payload.length > 0xffff) throw new RangeError('payload too long')

  const out = []
  let idx = 0
  let offset = 0
  while (offset < payload.length || out.length === 0) {
    const first = out.length === 0
    const header = first ? 3 : 1
    const take = Math.min(payload.length - offset, maxFragment - header)
    const buf = Buffer.alloc(header + take)
    buf.writeUInt8((first ? FIRST : 0) | (idx & 0x7f), 0)
    if (first) buf.writeUInt16BE(payload.length, 1)
    payload.copy(buf, header, offset, offset + take)
    out.push(buf)
    offset += take
    idx = (idx + 1) & 0x7f
  }
  return out
}

/**
 * Reassembles fragments into messages.
 * push() returns { message } when complete, { error } on a framing error
 * ('bad_request' | 'too_large'), or {} when more fragments are needed.
 */
export class Reassembler {
  constructor({ maxMessage = MAX_MESSAGE } = {}) {
    this.maxMessage = maxMessage
    this.reset()
  }

  reset() {
    this.total = 0
    this.chunks = []
    this.received = 0
    this.nextIdx = 0
    this.active = false
    this.discarding = false
  }

  /** True while a partial message is buffered. */
  get pending() {
    return this.active
  }

  push(buf) {
    if (buf.length < 1) return { error: 'bad_request' }

    const header = buf.readUInt8(0)
    const isFirst = (header & FIRST) !== 0
    const idx = header & 0x7f

    if (isFirst) {
      this.reset()
      if (buf.length < 3 || idx !== 0) return { error: 'bad_request' }

      const total = buf.readUInt16BE(1)
      if (total === 0) return { error: 'bad_request' }
      if (total > this.maxMessage) {
        this.discarding = true // drop continuations of this oversized message
        return { error: 'too_large' }
      }

      this.total = total
      this.active = true
      this.nextIdx = 1
      return this.#append(buf.subarray(3))
    }

    if (this.discarding) return {}
    if (!this.active || idx !== this.nextIdx) {
      this.reset()
      return { error: 'bad_request' }
    }

    this.nextIdx = (this.nextIdx + 1) & 0x7f
    return this.#append(buf.subarray(1))
  }

  #append(chunk) {
    this.chunks.push(chunk)
    this.received += chunk.length

    if (this.received > this.total) {
      this.reset()
      return { error: 'bad_request' }
    }
    if (this.received === this.total) {
      const message = Buffer.concat(this.chunks)
      this.reset()
      return { message }
    }
    return {}
  }
}
