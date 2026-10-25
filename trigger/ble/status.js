import { Flag, encodeStatus } from './protocol.js'

export const serializeJob = (j) => ({
  id: j.id,
  name: j.name,
  camera: j.camera,
  wakeUpTime: j.config.wakeUpTime.toISOString(),
  recordTime: j.config.recordTime.toISOString(),
  launchTime: j.config.launchTime.toISOString(),
  stopTime: j.config.stopTime.toISOString(),
  state: j.state,
})

/** Gathers live device state for the status op and the Status characteristic. */
export class StatusSource {
  constructor({ scheduler, system, pairing }) {
    this.scheduler = scheduler
    this.system = system
    this.pairing = pairing
  }

  snapshot() {
    const { scheduler, system, pairing } = this
    const next = scheduler.next
    const phase = scheduler.cam && !scheduler.cam.ready ? 'error' : scheduler.phase

    return {
      phase,
      recording: Boolean(scheduler.cam?.recording),
      cameraReady: Boolean(scheduler.cam?.ready),
      wifi: system.wifiEnabled,
      pairingOpen: pairing.isOpen,
      clockSet: system.clockSetSinceBoot,
      configRev: scheduler.config?.configRev ?? 0,
      attemptsRemaining: scheduler.attemptsRemaining,
      next: next
        ? { type: next.type, at: next.at.toISOString(), attempt: next.attempt }
        : null,
    }
  }

  /** JSON form returned by status.get. */
  toJson(s = this.snapshot()) {
    return {
      phase: s.phase,
      recording: s.recording,
      cameraReady: s.cameraReady,
      wifi: s.wifi,
      configRev: s.configRev,
      next: s.next,
    }
  }

  /** The 12-byte Status characteristic value. */
  toBinary(s = this.snapshot()) {
    let flags = 0
    if (s.recording) flags |= Flag.RECORDING
    if (s.cameraReady) flags |= Flag.CAMERA_READY
    if (s.wifi) flags |= Flag.WIFI
    if (s.pairingOpen) flags |= Flag.PAIRING_OPEN
    if (s.clockSet) flags |= Flag.CLOCK_SET

    return encodeStatus({
      flags,
      phase: s.phase,
      configRev: s.configRev,
      nextEventAt: s.next ? Math.floor(Date.parse(s.next.at) / 1000) : 0,
      nextEventType: s.next?.type ?? 'none',
      attemptsRemaining: s.attemptsRemaining,
    })
  }
}
