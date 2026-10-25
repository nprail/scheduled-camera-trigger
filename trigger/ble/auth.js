import { createHmac, randomBytes, timingSafeEqual } from 'crypto'

const CLIENT_LABEL = 'scs-auth-v1'
const SERVER_LABEL = 'scs-auth-v1-srv'

const mac = (secret, label, nonce, clientNonce) =>
  createHmac('sha256', secret)
    .update(label)
    .update(nonce)
    .update(clientNonce)
    .digest()

export const newNonce = () => randomBytes(16)

export const clientProof = (secret, nonce, clientNonce) =>
  mac(secret, CLIENT_LABEL, nonce, clientNonce)

export const serverProof = (secret, nonce, clientNonce) =>
  mac(secret, SERVER_LABEL, nonce, clientNonce)

/** The pairing window: opened by the physical button, closes on its own. */
export class PairingWindow {
  constructor({ durationMs = 120_000, now = () => Date.now() } = {}) {
    this.durationMs = durationMs
    this.now = now
    this.openUntil = 0
    this.listeners = new Set()
    this.timer = null
  }

  get isOpen() {
    return this.now() < this.openUntil
  }

  open() {
    this.openUntil = this.now() + this.durationMs
    clearTimeout(this.timer)
    this.timer = setTimeout(() => this.#changed(), this.durationMs + 5)
    this.timer.unref?.()
    this.#changed()
  }

  close() {
    this.openUntil = 0
    clearTimeout(this.timer)
    this.#changed()
  }

  onChange(fn) {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  #changed() {
    for (const fn of this.listeners) fn(this.isOpen)
  }
}

/** Failed-attempt tracking shared by all connections. */
export class Lockout {
  constructor({ now = () => Date.now(), threshold = 5, baseMs = 30_000, maxMs = 300_000 } = {}) {
    this.now = now
    this.threshold = threshold
    this.baseMs = baseMs
    this.maxMs = maxMs
    this.failures = 0
    this.level = 0
    this.until = 0
  }

  /** Seconds left in the lockout, or 0. */
  get retryAfter() {
    return Math.max(0, Math.ceil((this.until - this.now()) / 1000))
  }

  fail() {
    this.failures++
    if (this.failures >= this.threshold) {
      const ms = Math.min(this.baseMs * 2 ** this.level, this.maxMs)
      this.level++
      this.failures = 0
      this.until = this.now() + ms
    }
  }

  success() {
    this.failures = 0
    this.level = 0
    this.until = 0
  }
}

export const safeEqual = (a, b) =>
  a.length === b.length && timingSafeEqual(a, b)
