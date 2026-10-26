import { MAX_MESSAGE, ErrorCode, ProtocolError } from './protocol.js'
import { fragment, Reassembler } from './framing.js'
import { decodeRequest, encodeResponse, encodeError, encodeEvent } from './codec.js'

const MAX_QUEUED_EVENTS = 50

/**
 * One BLE connection's protocol state: reassembles Command writes, runs the
 * request through the dispatcher, and paces framed responses/events out over
 * the Response characteristic. Transport-agnostic so it can be unit tested.
 */
export class Link {
  /**
   * @param {object} opts
   * @param {{handle: Function}} opts.dispatcher
   * @param {(frag: Buffer, done: () => void) => boolean} opts.notify
   *   send one notification; call done() when sent; return false if the
   *   client isn't subscribed (the message is then dropped)
   * @param {() => number} opts.maxFragment current MTU - 3
   */
  constructor({
    dispatcher,
    notify,
    maxFragment,
    requestTimeoutMs = 10_000,
    reassemblyTimeoutMs = 5_000,
    logger,
  }) {
    this.dispatcher = dispatcher
    this.notify = notify
    this.maxFragment = maxFragment
    this.requestTimeoutMs = requestTimeoutMs
    this.reassemblyTimeoutMs = reassemblyTimeoutMs
    this.logger = logger

    this.reassembler = new Reassembler()
    this.reassemblyTimer = null
    this.inflight = false
    this.closed = false

    this.queue = []
    this.sending = false

    this.session = {
      authed: false,
      nonce: null,
      logSub: false,
      push: (obj) => this.pushEvent(obj),
    }
  }

  close() {
    this.closed = true
    clearTimeout(this.reassemblyTimer)
    this.queue = []
  }

  /** Handle a write to the Command characteristic. */
  onWrite(buf) {
    if (this.closed) return

    const { message, error } = this.reassembler.push(buf)

    if (this.reassembler.pending) {
      clearTimeout(this.reassemblyTimer)
      this.reassemblyTimer = setTimeout(() => {
        this.reassembler.reset()
        this.#error(0, ErrorCode.BAD_REQUEST, 'Timed out waiting for the next fragment')
      }, this.reassemblyTimeoutMs)
    } else {
      clearTimeout(this.reassemblyTimer)
    }

    if (error) {
      const code = error === 'too_large' ? ErrorCode.TOO_LARGE : ErrorCode.BAD_REQUEST
      this.#error(0, code, error === 'too_large' ? 'Message too large' : 'Bad frame')
      return
    }
    if (message) this.#handleMessage(message)
  }

  /** Queue an unsolicited event; dropped unless the client is authenticated. */
  pushEvent(obj) {
    if (this.closed || !this.session.authed) return
    this.#enqueue(obj, true)
  }

  #handleMessage(buf) {
    let req
    try {
      req = decodeRequest(buf)
    } catch (err) {
      // not even the id could be read, or the body is malformed
      const id = buf.length >= 2 ? buf[1] : 0
      return this.#error(id, ErrorCode.BAD_REQUEST, err.message)
    }
    if (this.inflight) {
      return this.#error(req.id, ErrorCode.BUSY, 'Another request is in progress')
    }

    this.inflight = true
    this.#run(req).catch((err) => {
      this.inflight = false
      this.logger?.log('ble', `Link error: ${err?.message ?? err}`)
    })
  }

  async #run(req) {
    let timer
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(
        () => reject(new ProtocolError(ErrorCode.INTERNAL, 'Request timed out')),
        this.requestTimeoutMs
      )
    })

    let reply
    let after
    try {
      const result = await Promise.race([
        this.dispatcher.handle(this.session, req.op, req.fields),
        timeout,
      ])
      reply = encodeResponse(req.op, req.id, result.fields)
      after = result.after
    } catch (err) {
      const e = err instanceof ProtocolError ? err : new ProtocolError(ErrorCode.INTERNAL, err?.message ?? String(err))
      reply = encodeError(req.id, e.code, { ...e.extra, message: e.message })
    } finally {
      clearTimeout(timer)
    }

    // the request is finished once its reply is queued: a client may send the next one at once
    this.inflight = false
    this.#enqueueBuf(reply, false, req.id)
    after?.()
  }

  #error(id, code, message, extra = {}) {
    this.#enqueueBuf(encodeError(id, code, { ...extra, message }), false, id)
  }

  #enqueue(obj, isEvent) {
    this.#enqueueBuf(encodeEvent(obj.ev, obj.d), isEvent)
  }

  #enqueueBuf(buf, isEvent, id = 0) {
    if (buf.length > MAX_MESSAGE) {
      if (isEvent) return
      buf = encodeError(id, ErrorCode.TOO_LARGE)
    }

    if (isEvent) {
      // under backpressure the oldest events are the least useful
      const events = this.queue.filter((q) => q.isEvent)
      if (events.length >= MAX_QUEUED_EVENTS) {
        this.queue.splice(this.queue.indexOf(events[0]), 1)
      }
    }

    this.queue.push({ buf, isEvent })
    this.#drain()
  }

  #drain() {
    if (this.sending || this.closed) return
    const item = this.queue.shift()
    if (!item) return

    this.sending = true
    const frags = fragment(item.buf, this.maxFragment())

    const sendNext = () => {
      if (this.closed) {
        this.sending = false
        return
      }
      const frag = frags.shift()
      if (!frag) {
        this.sending = false
        return this.#drain()
      }
      const accepted = this.notify(frag, sendNext)
      if (accepted === false) {
        // not subscribed (or gone): drop the rest of this message
        this.sending = false
        this.#drain()
      }
    }
    sendNext()
  }
}
