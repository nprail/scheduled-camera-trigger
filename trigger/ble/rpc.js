// Application-layer RPC over the BLE transport.
//
// Wire messages (UTF-8 JSON):
//   request:  { type: 'req', id: <number>, method: <string>, params?: <any> }
//   response: { type: 'res', id: <number>, ok: true,  data?: <any> }
//             { type: 'res', id: <number>, ok: false, error: <string> }
//   event:    { type: 'evt', name: <string>, data: <any> }
//
// Method handlers are pure async functions; the dispatcher wraps them
// into the response envelope and never throws.

export const createRpc = ({ scheduler, controller, logger }) => {
  const handlers = {
    getStatus: async () => buildStatus(scheduler),

    getConfig: async () => controller.getConfig(),

    setConfig: async (params) => {
      if (!params || typeof params !== 'object') {
        throw new Error('setConfig: params must be an object')
      }
      await controller.save(params)
      return { ok: true }
    },

    getLogs: async (params = {}) => {
      const { since, limit } = params || {}
      let logs = logger.logMessages
      if (since) logs = logs.filter((l) => l.timestamp > since)
      if (limit && limit > 0) logs = logs.slice(-limit)
      return logs
    },

    test: async () => {
      await controller.test()
      return { ok: true }
    },
  }

  return async (msg) => {
    const id = typeof msg?.id === 'number' ? msg.id : null
    const method = msg?.method

    if (!method || typeof method !== 'string') {
      return { type: 'res', id, ok: false, error: 'Missing or invalid "method"' }
    }
    const handler = handlers[method]
    if (!handler) {
      return { type: 'res', id, ok: false, error: `Unknown method: ${method}` }
    }

    try {
      const data = await handler(msg.params)
      return { type: 'res', id, ok: true, data }
    } catch (err) {
      logger.log('ble', `RPC ${method} failed`, { err: err?.message ?? String(err) })
      return { type: 'res', id, ok: false, error: err?.message ?? String(err) }
    }
  }
}

export const buildStatus = (scheduler) => ({
  timestamp: new Date().toISOString(),
  deviceId: scheduler.config?.deviceId,
  deviceName: scheduler.config?.deviceName,
  cameraType: scheduler.config?.cameraType,
  recording: scheduler.cam?.recording ?? false,
  jobs: scheduler.jobs ?? [],
})
