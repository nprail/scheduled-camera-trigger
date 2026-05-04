import bleno from '@stoprocent/bleno'

import { RX_UUID, TX_UUID, SERVICE_UUID } from './uuids.js'
import { Reassembler, buildFrames, SAFE_CHUNK_PAYLOAD } from './transport.js'

// Header size for our framing protocol (must match transport.js).
const HEADER_LEN = 4

/**
 * Build the GATT primary service together with a `send(msg)` helper that
 * notifies the currently-subscribed client. `onMessage` is invoked once
 * per fully-reassembled inbound JSON message.
 */
export const createGattService = ({ onMessage, logger }) => {
  const reasm = new Reassembler()

  let notifyFn = null
  let maxNotifyValueSize = 20 // updated on subscribe per current MTU
  let txMsgId = 0

  // Notification queue — drains a small burst of frames per event-loop tick to
  // avoid overwhelming the BLE stack while still keeping the pipeline filled.
  const TX_BURST = 5 // frames sent per drain tick before yielding
  const txQueue = []
  let txBusy = false

  const drainQueue = () => {
    if (txBusy || txQueue.length === 0 || !notifyFn) return
    txBusy = true

    let sent = 0
    while (txQueue.length > 0 && sent < TX_BURST) {
      const frame = txQueue.shift()
      if (notifyFn(frame) === false) {
        // BLE stack signalled back-pressure; re-queue and back off.
        txQueue.unshift(frame)
        break
      }
      sent++
    }

    setImmediate(() => {
      txBusy = false
      drainQueue()
    })
  }

  const nextTxId = () => {
    txMsgId = (txMsgId + 1) & 0xffff
    if (txMsgId === 0) txMsgId = 1
    return txMsgId
  }

  const rxChar = new bleno.Characteristic({
    uuid: RX_UUID,
    properties: ['write', 'writeWithoutResponse'],
    onWriteRequest: (data, offset, withoutResponse, callback) => {
      try {
        if (offset !== 0) {
          return callback(bleno.Characteristic.RESULT_ATTR_NOT_LONG)
        }

        const assembled = reasm.push(data)
        callback(bleno.Characteristic.RESULT_SUCCESS)

        if (!assembled) return

        let msg
        try {
          msg = JSON.parse(assembled.toString('utf8'))
        } catch (err) {
          logger.log('ble', 'Bad JSON received on RX', { err: err.message })
          return
        }

        Promise.resolve()
          .then(() => onMessage(msg))
          .catch((err) =>
            logger.log('ble', 'onMessage handler error', {
              err: err?.message ?? String(err),
            }),
          )
      } catch (err) {
        logger.log('ble', 'onWriteRequest threw', { err: err?.message })
        try {
          callback(bleno.Characteristic.RESULT_UNLIKELY_ERROR)
        } catch {}
      }
    },
  })

  const txChar = new bleno.Characteristic({
    uuid: TX_UUID,
    properties: ['notify'],
    onSubscribe: (maxValueSize, updateValueCallback) => {
      maxNotifyValueSize = Math.max(HEADER_LEN + 1, maxValueSize || 20)
      notifyFn = updateValueCallback
      logger.log('ble', 'Client subscribed to TX', { maxValueSize })
    },
    onUnsubscribe: () => {
      notifyFn = null
      txQueue.length = 0
      logger.log('ble', 'Client unsubscribed from TX')
    },
  })

  const service = new bleno.PrimaryService({
    uuid: SERVICE_UUID,
    characteristics: [rxChar, txChar],
  })

  /** Send a JSON message to the subscribed client. Returns false if no subscriber. */
  const send = (msg) => {
    if (!notifyFn) return false
    const payload = Buffer.from(JSON.stringify(msg), 'utf8')
    const chunkSize = Math.max(
      SAFE_CHUNK_PAYLOAD,
      maxNotifyValueSize - HEADER_LEN,
    )
    const frames = buildFrames(nextTxId(), payload, chunkSize)
    for (const f of frames) txQueue.push(f)
    drainQueue()
    return true
  }

  return { service, send, hasSubscriber: () => notifyFn != null }
}
