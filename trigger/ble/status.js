import { Flag, NO_ATTEMPT } from './protocol.js'
import { encodeStatus } from './codec.js'

/** Gathers live device state for the Status characteristic. */
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
      pairingOpen: pairing.isOpen,
      clockValid: system.clockSetSinceBoot,
      configRev: scheduler.config?.configRev ?? 0,
      attemptsRemaining: scheduler.attemptsRemaining,
      next: next ? { type: next.type, at: next.at, attempt: next.attempt, index: next.index } : null,
    }
  }

  /** The 13-byte Status characteristic value. */
  toBinary(s = this.snapshot()) {
    let flags = 0
    if (s.recording) flags |= Flag.RECORDING
    if (s.cameraReady) flags |= Flag.CAMERA_READY
    if (s.pairingOpen) flags |= Flag.PAIRING_OPEN
    if (s.clockValid) flags |= Flag.CLOCK_VALID

    return encodeStatus({
      flags,
      phase: s.phase,
      configRev: s.configRev,
      nextEventAt: s.next ? Math.floor(s.next.at.getTime() / 1000) : 0,
      nextEventType: s.next?.type ?? 'none',
      attemptsRemaining: s.attemptsRemaining,
      nextAttempt: s.next ? s.next.index : NO_ATTEMPT,
    })
  }
}
