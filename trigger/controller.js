import { EventEmitter } from 'events'
import { saveJson } from './lib/utils.js'
import { normalizeConfig, validateConfig, DEVICE_OWNED } from './lib/config.js'

export class ControllerError extends Error {
  constructor(code, message, field) {
    super(message)
    this.code = code
    this.field = field
  }
}

/**
 * Operations that change device state. Transport-agnostic: used by BLE
 * today, and could back the HTTP server too.
 *
 * Emits 'config' ({ configRev }) after a successful save.
 */
export const initController = ({
  configFile,
  scheduler,
  logger,
  system,
  now = () => Date.now(),
}) => {
  const events = new EventEmitter()

  const deviceName = (c) => (c.name ? `${c.name}-${c.deviceId}` : c.deviceId)

  const assertIdle = () => {
    if (scheduler.phase !== 'idle') {
      throw new ControllerError('busy', `Device is ${scheduler.phase}`)
    }
  }

  const test = async () => {
    if (scheduler.locked) throw new ControllerError('busy', 'Camera is busy')
    // runs in the background; progress arrives as scheduler 'test' events
    scheduler.runTest().catch((err) => logger.log('index', err?.message ?? err))
  }

  const save = async (rawConfig) => {
    assertIdle()

    const current = scheduler.config
    const next = normalizeConfig(rawConfig)

    for (const key of DEVICE_OWNED) delete next[key]
    next.deviceId = current.deviceId
    if (current.bleSecret) next.bleSecret = current.bleSecret
    next.configRev = (current.configRev ?? 0) + 1

    const errors = validateConfig(next, { now: now() })

    // the camera must be woken in the future for an attempt to run at all
    const lead = (next.startBefore + next.wakeUpTimeout) * 1000
    next.attempts?.forEach((a, i) => {
      const t = new Date(a.time).getTime()
      if (t > now() && t - lead <= now()) {
        errors.push({
          field: `attempts[${i}].time`,
          msg: 'is too soon: the camera wake-up time has already passed',
        })
      }
    })

    if (errors.length) {
      throw new ControllerError(
        'invalid_config',
        `${errors[0].field} ${errors[0].msg}`,
        errors[0].field
      )
    }

    next.deviceName = deviceName(next)
    await saveJson(configFile, next)

    scheduler.teardown()
    scheduler.initialize(next)

    events.emit('config', { configRev: next.configRev })
    return { configRev: next.configRev }
  }

  /** Persist a new BLE shared secret (hex). */
  const setSecret = async (secretHex) => {
    const config = { ...scheduler.config, bleSecret: secretHex }
    await saveJson(configFile, config)
    scheduler.config = config
  }

  /** Set the system clock and re-plan every timer against it. */
  const setTime = async (epochMs) => {
    assertIdle()

    const offsetMs = now() - epochMs
    await system.setTime(epochMs)
    scheduler.reinitialize()
    logger.log('index', 'Clock set', { offsetMs })

    return { offsetMs }
  }

  return Object.assign(events, { test, save, setSecret, setTime })
}
