/**
 * Config normalization + validation.
 *
 * Canonical shape (see docs/ble-protocol.md §4):
 * {
 *   name, cameraType, wakeUpTimeout, startBefore, endAfter, logFile,
 *   buttonGpioPort,
 *   generic: { releaseGpioPin, focusGpioPin },
 *   zcam: { cameraIp },
 *   attempts: [{ name, time }]
 * }
 *
 * Durations are seconds. Device-owned fields are never writable by clients.
 */

/** Keeps a full config under the 4096-byte BLE message limit. */
export const MAX_ATTEMPTS = 30

export const DEVICE_OWNED = ['deviceId', 'deviceName', 'bleSecret', 'configRev']

const num = (v) => {
  if (typeof v === 'number') return v
  if (typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) {
    return Number(v)
  }
  return v
}

/**
 * Migrate legacy config shapes to the canonical one. Pure; does not validate.
 */
export const normalizeConfig = (raw = {}) => {
  const c = structuredClone(raw)

  c.generic = c.generic ?? {}
  c.zcam = c.zcam ?? {}

  // legacy flat/misnamed keys
  const legacy = {
    releaseGpioPin: [c.genericReleaseGpioPin, c.generic.releaseGpioPort],
    focusGpioPin: [
      c.genericFocusGpioPin,
      c.generic.genericFocusGpioPin,
      c.generic.focusGpioPort,
    ],
  }
  for (const [key, candidates] of Object.entries(legacy)) {
    if (c.generic[key] == null) {
      c.generic[key] = candidates.find((v) => v != null)
    }
    if (c.generic[key] == null) delete c.generic[key]
  }
  if (c.zcam.cameraIp == null && c.zcamCameraIp != null) {
    c.zcam.cameraIp = c.zcamCameraIp
  }
  delete c.genericReleaseGpioPin
  delete c.genericFocusGpioPin
  delete c.zcamCameraIp
  delete c.generic.releaseGpioPort
  delete c.generic.focusGpioPort
  delete c.generic.genericFocusGpioPin

  for (const key of ['wakeUpTimeout', 'startBefore', 'endAfter']) {
    if (c[key] != null) c[key] = num(c[key])
  }
  if (c.generic.releaseGpioPin != null) {
    c.generic.releaseGpioPin = num(c.generic.releaseGpioPin)
  }
  if (c.generic.focusGpioPin != null) {
    c.generic.focusGpioPin = num(c.generic.focusGpioPin)
  }
  if (c.buttonGpioPort != null) c.buttonGpioPort = num(c.buttonGpioPort)

  // attempts: plain ISO strings -> { name, time }
  c.attempts = (c.attempts ?? []).map((a, i) =>
    typeof a === 'string' ? { name: `Attempt ${i + 1}`, time: a } : a
  )

  return c
}

const isIsoDate = (s) =>
  typeof s === 'string' && !Number.isNaN(new Date(s).getTime())

const isPin = (v) => Number.isInteger(v) && v >= 0 && v <= 40

/**
 * Validate a (normalized) config. Returns an array of { field, msg }.
 * `now` is epoch ms; attempts must lie in the future.
 */
export const validateConfig = (c, { now = Date.now() } = {}) => {
  const errors = []
  const err = (field, msg) => errors.push({ field, msg })

  if (c == null || typeof c !== 'object') {
    return [{ field: 'config', msg: 'must be an object' }]
  }

  if (c.name != null && (typeof c.name !== 'string' || c.name.length > 32)) {
    err('name', 'must be a string of at most 32 characters')
  }
  if (!['generic', 'zcam'].includes(c.cameraType)) {
    err('cameraType', "must be 'generic' or 'zcam'")
  }
  if (typeof c.logFile !== 'string' || c.logFile.trim() === '') {
    err('logFile', 'is required')
  }
  for (const key of ['wakeUpTimeout', 'startBefore', 'endAfter']) {
    const v = c[key]
    if (typeof v !== 'number' || !Number.isFinite(v) || v < 0 || v > 3600) {
      err(key, 'must be a number of seconds between 0 and 3600')
    }
  }
  if (c.buttonGpioPort != null && !isPin(c.buttonGpioPort)) {
    err('buttonGpioPort', 'must be a GPIO number')
  }

  if (c.cameraType === 'generic') {
    if (!isPin(c.generic?.releaseGpioPin)) {
      err('generic.releaseGpioPin', 'is required for generic cameras')
    }
    if (c.generic?.focusGpioPin != null && !isPin(c.generic.focusGpioPin)) {
      err('generic.focusGpioPin', 'must be a GPIO number')
    }
  }
  if (c.cameraType === 'zcam') {
    if (typeof c.zcam?.cameraIp !== 'string' || c.zcam.cameraIp.trim() === '') {
      err('zcam.cameraIp', 'is required for zcam cameras')
    }
  }

  if (!Array.isArray(c.attempts)) {
    err('attempts', 'must be an array')
  } else {
    if (c.attempts.length > MAX_ATTEMPTS) {
      err('attempts', `at most ${MAX_ATTEMPTS} attempts`)
    }
    const seen = new Set()
    c.attempts.forEach((a, i) => {
      const f = `attempts[${i}]`
      if (typeof a?.name !== 'string' || a.name === '' || a.name.length > 32) {
        err(`${f}.name`, 'must be a string of 1-32 characters')
      }
      if (!isIsoDate(a?.time)) {
        err(`${f}.time`, 'is not a valid date')
      } else {
        const t = new Date(a.time).getTime()
        if (t <= now) err(`${f}.time`, 'must be in the future')
        if (seen.has(t)) err(`${f}.time`, 'duplicates another attempt')
        seen.add(t)
      }
    })
  }

  return errors
}

/** Remove device-owned/secret fields before sending a config to a client. */
export const publicConfig = (config) => {
  const c = structuredClone(config)
  delete c.bleSecret
  delete c.deviceName
  return c
}
