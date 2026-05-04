import bleno from '@stoprocent/bleno'

import { SERVICE_UUID } from './uuids.js'
import { createGattService } from './service.js'
import { createRpc, buildStatus } from './rpc.js'

/**
 * Initialize the BLE peripheral. Replaces the previous HTTP server.
 *
 * Advertises one primary service (see uuids.js) with two characteristics:
 *   - RX: client writes framed JSON requests/notifications
 *   - TX: device notifies framed JSON responses/events
 *
 * Application protocol is documented in rpc.js.
 */
export const initBle = ({ scheduler, controller, logger }) => {
  const dispatch = createRpc({ scheduler, controller, logger })

  const onMessage = async (msg) => {
    if (msg?.type !== 'req') {
      // Ignore stray non-request payloads from clients.
      return
    }
    const response = await dispatch(msg)
    send(response)
  }

  const { service, send, hasSubscriber } = createGattService({
    onMessage,
    logger,
  })

  // Stream every new log line to the connected client.
  const unsubscribeLogs = logger.subscribe((entry) => {
    if (!hasSubscriber()) return
    try {
      send({ type: 'evt', name: 'log', data: entry })
    } catch (err) {
      // Avoid recursing back into logger here.
      console.error('[ble] failed to forward log event:', err)
    }
  })

  const localName = String(scheduler.config?.deviceName || 'CamTrigger').slice(
    0,
    20 // BLE local-name in advertisement is limited
  )

  bleno.on('stateChange', (state) => {
    logger.log('ble', `Adapter state changed: ${state}`)
    if (state === 'poweredOn') {
      bleno.startAdvertising(localName, [SERVICE_UUID], (err) => {
        if (err) logger.log('ble', 'startAdvertising error', { err: err.message })
      })
    } else {
      bleno.stopAdvertising()
    }
  })

  bleno.on('advertisingStart', (err) => {
    if (err) {
      logger.log('ble', 'advertisingStart error', { err: err.message })
      return
    }
    bleno.setServices([service], (e) => {
      if (e) {
        logger.log('ble', 'setServices error', { err: e.message })
      } else {
        logger.log('ble', 'GATT service registered', {
          name: localName,
          service: SERVICE_UUID,
        })
      }
    })
  })

  bleno.on('accept', (clientAddress) => {
    logger.log('ble', 'Client connected', { clientAddress })
    // Push current status so the client doesn't have to immediately poll.
    try {
      send({ type: 'evt', name: 'status', data: buildStatus(scheduler) })
    } catch {}
  })

  bleno.on('disconnect', (clientAddress) => {
    logger.log('ble', 'Client disconnected', { clientAddress })
  })

  return {
    stop: () => {
      unsubscribeLogs()
      try {
        bleno.stopAdvertising()
      } catch {}
    },
  }
}
