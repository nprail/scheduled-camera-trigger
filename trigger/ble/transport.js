// Binary chunked framing used over the RX/TX BLE characteristics.
//
// Frame layout (4-byte header + payload bytes):
//   bytes 0-1: u16 BE  msgId       (sender-assigned reassembly key)
//   byte 2:    u8      seq         (chunk index, 0..255)
//   byte 3:    u8      flags       (bit 0 = LAST chunk)
//   bytes 4+:  payload chunk
//
// The reassembled payload is a single UTF-8 JSON document. The application
// layer (rpc.js) decides whether it is a request, response, or event.

const HEADER_LEN = 4
export const FLAG_LAST = 0x01

// Conservative default that fits an unnegotiated MTU of 27 (ATT payload 23).
// Both peers may use larger chunks once the link MTU is known.
export const SAFE_CHUNK_PAYLOAD = 19

export class Reassembler {
  constructor() {
    this.buffers = new Map() // msgId -> { chunks: Map<seq, Buffer>, lastSeq?: number }
  }

  /**
   * Push one received frame. Returns the assembled payload Buffer when the
   * message is complete, otherwise null.
   */
  push(frame) {
    if (!Buffer.isBuffer(frame) || frame.length < HEADER_LEN) return null

    const msgId = frame.readUInt16BE(0)
    const seq = frame.readUInt8(2)
    const flags = frame.readUInt8(3)
    const payload = frame.subarray(HEADER_LEN)

    let entry = this.buffers.get(msgId)
    if (!entry) {
      entry = { chunks: new Map() }
      this.buffers.set(msgId, entry)
    }

    entry.chunks.set(seq, payload)
    if (flags & FLAG_LAST) entry.lastSeq = seq

    if (entry.lastSeq === undefined) return null
    for (let i = 0; i <= entry.lastSeq; i++) {
      if (!entry.chunks.has(i)) return null
    }

    const parts = []
    for (let i = 0; i <= entry.lastSeq; i++) parts.push(entry.chunks.get(i))
    this.buffers.delete(msgId)
    return Buffer.concat(parts)
  }
}

/** Split a payload Buffer into framed chunks ready for write/notify. */
export const buildFrames = (msgId, payload, chunkSize = SAFE_CHUNK_PAYLOAD) => {
  if (chunkSize < 1) chunkSize = 1

  const frames = []

  if (payload.length === 0) {
    const f = Buffer.alloc(HEADER_LEN)
    f.writeUInt16BE(msgId & 0xffff, 0)
    f.writeUInt8(0, 2)
    f.writeUInt8(FLAG_LAST, 3)
    return [f]
  }

  let seq = 0
  for (let off = 0; off < payload.length; off += chunkSize) {
    const end = Math.min(off + chunkSize, payload.length)
    const isLast = end >= payload.length
    const chunk = payload.subarray(off, end)

    const f = Buffer.alloc(HEADER_LEN + chunk.length)
    f.writeUInt16BE(msgId & 0xffff, 0)
    f.writeUInt8(seq & 0xff, 2)
    f.writeUInt8(isLast ? FLAG_LAST : 0, 3)
    chunk.copy(f, HEADER_LEN)

    frames.push(f)
    if (++seq > 256) {
      throw new Error(
        `Message too large: exceeds 256 chunks at chunkSize=${chunkSize}`
      )
    }
  }
  return frames
}
