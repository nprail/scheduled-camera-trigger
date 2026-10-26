import { PairingWindow, Lockout } from './auth.js'
import { createDispatcher, toLogLine } from './dispatcher.js'
import { Link } from './link.js'
import { StatusSource } from './status.js'
import { PROTOCOL_VERSION } from './protocol.js'
import { encodeDeviceInfo, truncateUtf8 } from './codec.js'
import { initButton } from '../lib/button.js'

const STATUS_MIN_INTERVAL_MS = 100
const STATUS_RECORDING_INTERVAL_MS = 1000

/**
 * Wires the protocol to the scheduler/controller and (optionally) a GATT
 * transport. `start: false` builds everything but doesn't touch hardware,
 * which is what the tests use.
 */
export const createBle = ({ scheduler, controller, logger, system }) => {
  const pairing = new PairingWindow()
  const lockout = new Lockout()
  const status = new StatusSource({ scheduler, system, pairing })
  const dispatcher = createDispatcher({
    scheduler,
    controller,
    logger,
    system,
    pairing,
    lockout,
    status,
  })

  const links = new Set()
  const broadcast = (obj) => links.forEach((l) => l.pushEvent(obj))

  // --- events ---------------------------------------------------------
  scheduler.on('job', (j) =>
    broadcast({
      ev: 'job',
      d: { attempt: j.index, step: j.step, at: Math.floor(j.at.getTime() / 1000) },
    })
  )
  scheduler.on('test', (d) =>
    broadcast({ ev: 'test', d: { step: d.step, ok: Boolean(d.ok), error: d.error ?? '' } })
  )
  controller.on('config', (d) => broadcast({ ev: 'config', d: { configRev: d.configRev } }))
  logger.on('log', (l) =>
    links.forEach((link) => {
      if (link.session.logSub) link.pushEvent({ ev: 'log', d: toLogLine(l) })
    })
  )

  // --- Status characteristic -------------------------------------------
  let publish = () => {}
  let lastStatus = null
  let statusTimer = null

  const scheduleStatus = () => {
    if (statusTimer) return
    const interval =
      scheduler.phase === 'recording' ? STATUS_RECORDING_INTERVAL_MS : STATUS_MIN_INTERVAL_MS
    statusTimer = setTimeout(() => {
      statusTimer = null
      const value = status.toBinary()
      if (lastStatus && value.equals(lastStatus)) return
      lastStatus = value
      publish(value)
    }, interval)
    statusTimer.unref?.()
  }
  scheduler.on('change', scheduleStatus)
  pairing.onChange(scheduleStatus)

  const makeLink = ({ notify, maxFragment }) =>
    new Link({ dispatcher, notify, maxFragment, logger })

  const deviceInfo = () => {
    const c = scheduler.config
    return encodeDeviceInfo({
      v: PROTOCOL_VERSION,
      id: c.deviceId,
      name: c.name ?? '',
      cam: c.cameraType,
      fw: process.env.npm_package_version ?? '1.0.0',
    })
  }

  /** `{name}-{id}`, or just the id. The name is cut so the id always fits the 29-byte advertised name. */
  const advertisedName = () => {
    const c = scheduler.config
    return c.name ? `${truncateUtf8(c.name, 22)}-${c.deviceId}` : c.deviceId
  }

  const start = async () => {
    // loaded lazily: needs the native HCI socket, which only exists on the Pi
    const { startGattServer } = await import('./gatt.js')
    const requireEncryption = process.env.BLE_REQUIRE_ENCRYPTION !== '0'
    const server = await startGattServer({
      deviceName: advertisedName(),
      deviceInfo,
      readStatus: () => status.toBinary(),
      makeLink,
      onConnect: (link) => links.add(link),
      onDisconnect: (link) => links.delete(link),
      requireEncryption,
      logger,
    })
    publish = server.notifyStatus

    // Button: open the pairing window
    const disposeButton = await initButton({
      port: scheduler.config.buttonGpioPort,
      logger,
      onPress: () => {
        pairing.open()
        logger.log('ble', 'Pairing window open for 120 s')
      },
    })

    return {
      close: () => {
        disposeButton()
        server.close()
      },
    }
  }

  return { start, makeLink, dispatcher, pairing, lockout, status, links, deviceInfo, advertisedName }
}

export const initBle = (deps) => createBle(deps).start()
