// Constants shared by the BLE implementation. See docs/BLE_PROTOCOL.md.

export const PROTOCOL_VERSION = 1
export const MAX_MESSAGE = 4096

export const UUID = {
  service: '7a3c0001-5e9b-4d2a-8f61-c0a7b2d4e9f3',
  deviceInfo: '7a3c0002-5e9b-4d2a-8f61-c0a7b2d4e9f3',
  status: '7a3c0003-5e9b-4d2a-8f61-c0a7b2d4e9f3',
  command: '7a3c0004-5e9b-4d2a-8f61-c0a7b2d4e9f3',
  response: '7a3c0005-5e9b-4d2a-8f61-c0a7b2d4e9f3',
}

export const ErrorCode = {
  BAD_REQUEST: 'bad_request',
  UNAUTHORIZED: 'unauthorized',
  LOCKED_OUT: 'locked_out',
  NOT_SUPPORTED: 'not_supported',
  BUSY: 'busy',
  INVALID_CONFIG: 'invalid_config',
  TOO_LARGE: 'too_large',
  PAIRING_CLOSED: 'pairing_closed',
  INTERNAL: 'internal',
}

export class ProtocolError extends Error {
  constructor(code, msg, extra = {}) {
    super(msg)
    this.code = code
    this.extra = extra
  }
}

// Status characteristic
export const Flag = {
  RECORDING: 1 << 0,
  CAMERA_READY: 1 << 1,
  PAIRING_OPEN: 1 << 2,
  CLOCK_SET: 1 << 3,
}
export const PHASE = { idle: 0, awake: 1, recording: 2, test: 3, error: 4 }
export const EVENT_TYPE = { none: 0, wake: 1, start: 2, stop: 3 }

/** Encode the fixed 12-byte Status value. */
export const encodeStatus = ({
  flags,
  phase,
  configRev,
  nextEventAt,
  nextEventType,
  attemptsRemaining,
}) => {
  const b = Buffer.alloc(12)
  b.writeUInt8(flags & 0xff, 0)
  b.writeUInt8(PHASE[phase] ?? PHASE.error, 1)
  b.writeUInt32LE(configRev >>> 0, 2)
  b.writeUInt32LE(nextEventAt >>> 0, 6)
  b.writeUInt8(EVENT_TYPE[nextEventType] ?? 0, 10)
  b.writeUInt8(Math.min(attemptsRemaining, 255), 11)
  return b
}
