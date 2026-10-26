// Conversion between the device's config.json shape and the wire config of protocol v2.
import { FIELD, REASON } from './protocol.js'

/** device config -> wire config (docs/ble-protocol.md, Config layout) */
export const toWireConfig = (c) => ({
  cameraType: c.cameraType,
  wakeUpTimeout: c.wakeUpTimeout,
  startBefore: c.startBefore,
  endAfter: c.endAfter,
  cameraIp: c.zcam?.cameraIp || null,
  name: c.name ?? '',
  attempts: c.attempts.map((a) => ({ time: Math.floor(Date.parse(a.time) / 1000), name: a.name })),
})

/**
 * wire config -> device config. Fields the wire doesn't carry come from `current`: the log file and the GPIO
 * pins, which are wiring and live only in config.json.
 */
export const fromWireConfig = (w, current = {}) => {
  const c = {
    name: w.name,
    cameraType: w.cameraType,
    wakeUpTimeout: w.wakeUpTimeout,
    startBefore: w.startBefore,
    endAfter: w.endAfter,
    logFile: current.logFile ?? 'log.txt',
    generic: { ...current.generic },
    zcam: {},
    attempts: w.attempts.map((a) => ({ name: a.name, time: new Date(a.time * 1000).toISOString() })),
  }
  if (current.buttonGpioPort != null) c.buttonGpioPort = current.buttonGpioPort
  if (w.cameraIp != null) c.zcam.cameraIp = w.cameraIp
  return c
}

const FIELD_BY_PATH = {
  name: FIELD.name,
  cameraType: FIELD.cameraType,
  wakeUpTimeout: FIELD.wakeUpTimeout,
  startBefore: FIELD.startBefore,
  endAfter: FIELD.endAfter,
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
