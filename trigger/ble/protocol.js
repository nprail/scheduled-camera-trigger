// Constants shared by the BLE implementation. See docs/ble-protocol.md.

export const PROTOCOL_VERSION = 2
export const MAX_MESSAGE = 2048

export const UUID = {
  service: '6b610001-6169-4f72-8f73-b3e1c5a97d24',
  deviceInfo: '6b610002-6169-4f72-8f73-b3e1c5a97d24',
  status: '6b610003-6169-4f72-8f73-b3e1c5a97d24',
  command: '6b610004-6169-4f72-8f73-b3e1c5a97d24',
  response: '6b610005-6169-4f72-8f73-b3e1c5a97d24',
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

/** Numeric error codes on the wire. */
export const ERROR_ID = {
  bad_request: 1,
  unauthorized: 2,
  locked_out: 3,
  not_supported: 4,
  busy: 5,
  invalid_config: 6,
  too_large: 7,
  pairing_closed: 8,
  internal: 9,
}

/** `extra` carries the structured detail of an error (retryAfter, or field/index/reason for invalid_config). */
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
  CLOCK_VALID: 1 << 3,
}
export const PHASE = { idle: 0, awake: 1, recording: 2, test: 3, error: 4 }
export const EVENT_TYPE = { none: 0, wake: 1, start: 2, stop: 3 }
export const NO_ATTEMPT = 0xff

export const JOB_STATE = { pending: 0, active: 1, complete: 2, missed: 3 }
export const JOB_STEP = { wake: 0, start: 1, stop: 2, complete: 3, missed: 4 }
export const TEST_STEP = { wake: 0, start: 1, stop: 2, sleep: 3, done: 4 }
export const CAMERA_TYPE = { generic: 0, zcam: 1 }

/** invalid_config field ids and reasons (docs/ble-protocol.md, Errors). */
export const FIELD = {
  name: 1, cameraType: 2, wakeUpTimeout: 3, startBefore: 4, endAfter: 5,
  buttonPin: 6, releasePin: 7, focusPin: 8, cameraIp: 9,
  attempts: 10, attemptName: 11, attemptTime: 12,
}
export const REASON = {
  required: 1, range: 2, bad_value: 3, past: 4, duplicate: 5, too_soon: 6, too_many: 7,
}
