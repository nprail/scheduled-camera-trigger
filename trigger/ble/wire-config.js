// Conversion between the device's config.json shape and the wire config of protocol v2.
import { FIELD, REASON } from './protocol.js'

/** device config -> wire config (docs/ble-protocol.md, Config layout) */
export const toWireConfig = (c) => ({
  cameraType: c.cameraType,
  wakeUpTimeout: c.wakeUpTimeout,
  startBefore: c.startBefore,
  endAfter: c.endAfter,
  buttonPin: c.buttonGpioPort ?? null,
  releasePin: c.generic?.releaseGpioPin ?? null,
  focusPin: c.generic?.focusGpioPin ?? null,
  cameraIp: c.zcam?.cameraIp || null,
  name: c.name ?? '',
  attempts: c.attempts.map((a) => ({ time: Math.floor(Date.parse(a.time) / 1000), name: a.name })),
})

/** wire config -> device config. Fields the wire doesn't carry (logFile) come from `current`. */
export const fromWireConfig = (w, current = {}) => {
  const c = {
    name: w.name,
    cameraType: w.cameraType,
    wakeUpTimeout: w.wakeUpTimeout,
    startBefore: w.startBefore,
    endAfter: w.endAfter,
    logFile: current.logFile ?? 'log.txt',
    generic: {},
    zcam: {},
    attempts: w.attempts.map((a) => ({ name: a.name, time: new Date(a.time * 1000).toISOString() })),
  }
  if (w.buttonPin != null) c.buttonGpioPort = w.buttonPin
  if (w.releasePin != null) c.generic.releaseGpioPin = w.releasePin
  if (w.focusPin != null) c.generic.focusGpioPin = w.focusPin
  if (w.cameraIp != null) c.zcam.cameraIp = w.cameraIp
  return c
}

const FIELD_BY_PATH = {
  name: FIELD.name,
  cameraType: FIELD.cameraType,
  wakeUpTimeout: FIELD.wakeUpTimeout,
  startBefore: FIELD.startBefore,
  endAfter: FIELD.endAfter,
  buttonGpioPort: FIELD.buttonPin,
  'generic.releaseGpioPin': FIELD.releasePin,
  'generic.focusGpioPin': FIELD.focusPin,
  'zcam.cameraIp': FIELD.cameraIp,
  attempts: FIELD.attempts,
}

/** A validation error (field path + reason name) -> the wire detail { field, index, reason }. */
export const toWireError = (path, reason) => {
  const m = /^attempts\[(\d+)\]\.(name|time)$/.exec(path ?? '')
  if (m) {
    return { field: m[2] === 'name' ? FIELD.attemptName : FIELD.attemptTime, index: Number(m[1]), reason: REASON[reason] ?? 0 }
  }
  return { field: FIELD_BY_PATH[path] ?? 0, index: 0, reason: REASON[reason] ?? 0 }
}
