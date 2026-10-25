import { ControllerError } from '../controller.js'
import { publicConfig } from '../lib/config.js'
import { PROTOCOL_VERSION, MAX_MESSAGE, ErrorCode, ProtocolError } from './protocol.js'
import { newNonce, clientProof, serverProof, safeEqual } from './auth.js'
import { serializeJob } from './status.js'

/** Handlers return `{ d, [AFTER]: fn }` to run `fn` after the response is queued. */
export const AFTER = Symbol('after')

const b64 = (buf) => buf.toString('base64')

const decodeB64 = (value, bytes, field) => {
  const buf = typeof value === 'string' ? Buffer.from(value, 'base64') : null
  if (!buf || buf.length !== bytes) {
    throw new ProtocolError(ErrorCode.BAD_REQUEST, `${field} must be ${bytes} bytes, base64`)
  }
  return buf
}

const mapControllerError = (err) => {
  if (!(err instanceof ControllerError)) return err
  const code = {
    busy: ErrorCode.BUSY,
    invalid_config: ErrorCode.INVALID_CONFIG,
  }[err.code] ?? ErrorCode.INTERNAL
  return new ProtocolError(code, err.message, err.field ? { field: err.field } : {})
}

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

  const requireBool = (v, field) => {
    if (typeof v !== 'boolean') {
      throw new ProtocolError(ErrorCode.BAD_REQUEST, `${field} must be a boolean`)
    }
  }

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
          nonce: b64(session.nonce),
          authRequired: true,
          paired: secret() !== null,
          now: new Date(now()).toISOString(),
          maxMessage: MAX_MESSAGE,
        }
      },
    },

    auth: {
      auth: false,
      run: async (session, d) => {
        if (!session.nonce) {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'hello required first')
        }
        if (lockout.retryAfter > 0) {
          throw new ProtocolError(ErrorCode.LOCKED_OUT, 'Too many failed attempts', {
            retryAfter: lockout.retryAfter,
          })
        }
        const key = secret()
        const clientNonce = decodeB64(d?.clientNonce, 16, 'clientNonce')
        const proof = decodeB64(d?.proof, 32, 'proof')

        const ok =
          key !== null && safeEqual(proof, clientProof(key, session.nonce, clientNonce))
        if (!ok) {
          lockout.fail()
          throw new ProtocolError(ErrorCode.UNAUTHORIZED, 'Bad proof')
        }

        lockout.success()
        session.authed = true
        return { proof: b64(serverProof(key, session.nonce, clientNonce)) }
      },
    },

    'auth.pair': {
      auth: false,
      run: async (session, d) => {
        if (!pairing.isOpen) {
          throw new ProtocolError(ErrorCode.PAIRING_CLOSED, 'Press the device button to pair')
        }
        const key = decodeB64(d?.secret, 16, 'secret')
        await controller.setSecret(key.toString('hex'))
        pairing.close()
        lockout.success()
        session.authed = true
        return {}
      },
    },

    'time.get': {
      auth: false,
      run: async () => ({ now: new Date(now()).toISOString() }),
    },

    'time.set': {
      run: async (session, d) => {
        const ms = typeof d?.now === 'string' ? Date.parse(d.now) : NaN
        if (Number.isNaN(ms)) {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'now must be an ISO 8601 date')
        }
        const { offsetMs } = await controller.setTime(ms)
        return { now: new Date(now()).toISOString(), offsetMs }
      },
    },

    'status.get': { run: async () => status.toJson() },

    'config.get': {
      run: async () => ({ config: publicConfig(scheduler.config) }),
    },

    'config.set': {
      run: async (session, d) => {
        if (d?.config == null || typeof d.config !== 'object') {
          throw new ProtocolError(ErrorCode.BAD_REQUEST, 'config is required')
        }
        const { configRev } = await controller.save(d.config)
        return { configRev }
      },
    },

    'jobs.get': {
      run: async (session, d) => {
        const offset = Number.isInteger(d?.offset) && d.offset > 0 ? d.offset : 0
        const limit = Math.min(Number.isInteger(d?.limit) ? d.limit : 10, 50)
        const all = scheduler.jobs
        const jobs = []
        let size = 0
        for (const job of all.slice(offset, offset + limit)) {
          const entry = serializeJob(job)
          size += JSON.stringify(entry).length + 1
          if (size > MAX_MESSAGE - 256) break
          jobs.push(entry)
        }
        return { jobs, total: all.length, more: offset + jobs.length < all.length }
      },
    },

    'camera.wake': { run: camera('wake') },
    'camera.start': { run: camera('start') },
    'camera.stop': { run: camera('stop') },
    'camera.sleep': { run: camera('sleep') },

    'test.run': {
      run: async () => {
        await controller.test()
        return { started: true }
      },
    },

    'logs.get': {
      run: async (session, d) => {
        const after = Number.isInteger(d?.after) ? d.after : 0
        const limit = Math.min(Number.isInteger(d?.limit) ? d.limit : 20, 100)
        const candidates = logger.since(after, limit + 1)
        const logs = []
        let size = 0
        for (const l of candidates.slice(0, limit)) {
          const entry = toLogEntry(l)
          size += JSON.stringify(entry).length + 1
          if (size > MAX_MESSAGE - 256) break
          logs.push(entry)
        }
        const last = logs.length ? logs[logs.length - 1].seq : after
        return { logs, last, more: candidates.length > logs.length }
      },
    },

    'logs.subscribe': {
      run: async (session, d) => {
        requireBool(d?.enabled, 'enabled')
        session.logSub = d.enabled
        const after = Number.isInteger(d?.after) ? d.after : logger.seq
        return {
          d: {},
          [AFTER]: () => {
            if (!session.logSub) return
            for (const l of logger.since(after, 100)) {
              session.push({ ev: 'log', d: toLogEntry(l) })
            }
          },
        }
      },
    },

    'wifi.set': {
      run: async (session, d) => {
        requireBool(d?.enabled, 'enabled')
        await controller.setWifi(d.enabled)
        return { enabled: d.enabled }
      },
    },
  }

  /**
   * @returns {Promise<{ d: object, after?: Function }>}
   * @throws {ProtocolError}
   */
  const handle = async (session, op, d) => {
    const entry = Object.hasOwn(ops, op) ? ops[op] : undefined
    if (!entry) throw new ProtocolError(ErrorCode.NOT_SUPPORTED, `Unknown op '${op}'`)
    if (entry.auth !== false && !session.authed) {
      throw new ProtocolError(ErrorCode.UNAUTHORIZED, 'Authentication required')
    }

    try {
      const result = await entry.run(session, d ?? {})
      if (result && Object.hasOwn(result, 'd') && result[AFTER]) {
        return { d: result.d, after: result[AFTER] }
      }
      return { d: result }
    } catch (err) {
      const mapped = mapControllerError(err)
      if (mapped instanceof ProtocolError) throw mapped
      logger.log('ble', `Handler '${op}' failed: ${mapped?.message ?? mapped}`)
      throw new ProtocolError(ErrorCode.INTERNAL, mapped?.message ?? String(mapped))
    }
  }

  return { handle }
}

export const toLogEntry = (l) => ({
  seq: l.seq,
  ts: l.timestamp,
  ns: l.namespace,
  msg: l.message,
})
