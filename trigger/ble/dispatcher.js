import { ControllerError } from '../controller.js'
import { PROTOCOL_VERSION, MAX_MESSAGE, ErrorCode, ProtocolError } from './protocol.js'
import { newNonce, clientProof, serverProof, safeEqual } from './auth.js'
import { toWireConfig, fromWireConfig, toWireError } from './wire-config.js'
import { encodeResponse } from './codec.js'

/** Handlers return `{ fields, [AFTER]: fn }` to run `fn` after the response is queued. */
export const AFTER = Symbol('after')

const mapControllerError = (err) => {
  if (!(err instanceof ControllerError)) return err
  if (err.code === 'invalid_config') {
    return new ProtocolError(ErrorCode.INVALID_CONFIG, err.message, toWireError(err.field, err.reason))
  }
  const code = err.code === 'busy' ? ErrorCode.BUSY : ErrorCode.INTERNAL
  return new ProtocolError(code, err.message)
}

/** A logger entry as a wire log line. */
export const toLogLine = (l) => ({
  seq: l.seq,
  ts: Math.floor(Date.parse(l.timestamp) / 1000),
  ns: l.namespace,
  msg: l.message,
})

export const createDispatcher = ({
  scheduler,
  controller,
  logger,
  system,
  pairing,
  lockout,
  status,
  now = () => scheduler.now(),
}) => {
  const secret = () =>
    scheduler.config?.bleSecret ? Buffer.from(scheduler.config.bleSecret, 'hex') : null

  const camera = (method) => async () => {
    if (scheduler.locked) throw new ProtocolError(ErrorCode.BUSY, 'Camera is busy')
    if (!scheduler.cam) {
      throw new ProtocolError(ErrorCode.INTERNAL, 'Camera is not available')
    }
    await scheduler.cam[method]()
    scheduler.emit('change')
    return { recording: Boolean(scheduler.cam.recording) }
  }

  const ops = {
    hello: {
      auth: false,
      run: async (session) => {
        session.nonce = newNonce()
        return {
          v: PROTOCOL_VERSION,
          paired: secret() !== null,
          nonceHex: session.nonce.toString('hex'),
          nowMs: now(),
          maxMessage: MAX_MESSAGE,
        }
      },
    },

    auth: {
      auth: false,
      run: async (session, f) => {
        if (!session.nonce) {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'hello required first')
        }
        if (lockout.retryAfter > 0) {
          throw new ProtocolError(ErrorCode.LOCKED_OUT, 'Too many failed attempts', {
            retryAfter: lockout.retryAfter,
          })
        }
        const key = secret()
        const clientNonce = Buffer.from(f.clientNonceHex, 'hex')
        const proof = Buffer.from(f.proofHex, 'hex')

        const ok =
          key !== null && safeEqual(proof, clientProof(key, session.nonce, clientNonce))
        if (!ok) {
          lockout.fail()
          throw new ProtocolError(ErrorCode.UNAUTHORIZED, 'Bad proof')
        }

        lockout.success()
        session.authed = true
        return { proofHex: serverProof(key, session.nonce, clientNonce).toString('hex') }
      },
    },

    'auth.pair': {
      auth: false,
      run: async (session, f) => {
        if (!pairing.isOpen) {
          throw new ProtocolError(ErrorCode.PAIRING_CLOSED, 'Press the device button to pair')
        }
        await controller.setSecret(f.secretHex)
        pairing.close()
        lockout.success()
        session.authed = true
        return {}
      },
    },

    'time.get': {
      auth: false,
      run: async () => ({ nowMs: now() }),
    },

    'time.set': {
      run: async (session, f) => {
        const { offsetMs } = await controller.setTime(f.nowMs)
        return { nowMs: now(), offsetMs }
      },
    },

    'config.get': {
      run: async () => ({ config: toWireConfig(scheduler.config) }),
    },

    'config.set': {
      run: async (session, f) => {
        const { configRev } = await controller.save(fromWireConfig(f.config, scheduler.config))
        return { configRev }
      },
    },

    // one state per attempt, in config order; the phone derives the times
    'jobs.get': {
      run: async () => ({ states: scheduler.jobs.map((j) => j.state) }),
    },

    'camera.wake': { run: camera('wake') },
    'camera.start': { run: camera('start') },
    'camera.stop': { run: camera('stop') },
    'camera.sleep': { run: camera('sleep') },

    'test.run': {
      run: async () => {
        await controller.test()
        return {}
      },
    },

    'logs.get': {
      run: async (session, f) => {
        const limit = Math.min(f.limit || 20, 100)
        const candidates = logger.since(f.after, limit + 1)
        const lines = []
        let size = 0
        for (const l of candidates.slice(0, limit)) {
          const line = toLogLine(l)
          size += 10 + Buffer.byteLength(line.ns) + Buffer.byteLength(line.msg)
          if (size > MAX_MESSAGE - 64) break
          lines.push(line)
        }
        const last = lines.length ? lines[lines.length - 1].seq : f.after
        return { last, more: candidates.length > lines.length, lines }
      },
    },

    'logs.subscribe': {
      run: async (session, f) => {
        session.logSub = f.enabled
        return {
          fields: {},
          [AFTER]: () => {
            if (!session.logSub) return
            for (const l of logger.since(f.after, 100)) {
              session.push({ ev: 'log', d: toLogLine(l) })
            }
          },
        }
      },
    },
  }

  /**
   * @param {object} session the connection's state
   * @param {string|null} op op name, or null for an unknown op code
   * @param {object} fields decoded request fields
   * @returns {Promise<{ fields: object, after?: Function }>}
   * @throws {ProtocolError}
   */
  const handle = async (session, op, fields) => {
    const entry = op && Object.hasOwn(ops, op) ? ops[op] : undefined
    if (!entry) throw new ProtocolError(ErrorCode.NOT_SUPPORTED, `Unknown op '${op}'`)
    if (entry.auth !== false && !session.authed) {
      throw new ProtocolError(ErrorCode.UNAUTHORIZED, 'Authentication required')
    }

    try {
      const result = await entry.run(session, fields ?? {})
      if (result && Object.hasOwn(result, 'fields') && result[AFTER]) {
        return { fields: result.fields, after: result[AFTER] }
      }
      return { fields: result }
    } catch (err) {
      const mapped = mapControllerError(err)
      if (mapped instanceof ProtocolError) throw mapped
      logger.log('ble', `Handler '${op}' failed: ${mapped?.message ?? mapped}`)
      throw new ProtocolError(ErrorCode.INTERNAL, mapped?.message ?? String(mapped))
    }
  }

  return { handle, encodeResponse }
}
