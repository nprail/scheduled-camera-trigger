import HciSocket from 'hci-socket'
import NodeBleHost from 'ble-host'

import { UUID } from './protocol.js'

const { BleManager, AdvertisingDataBuilder, HciErrors, AttErrors } = NodeBleHost

// Complete local name must fit the 31-byte scan response (2 bytes of header).
const MAX_ADV_NAME = 29

/**
 * Thin ble-host wrapper: GATT table + advertising. All protocol logic lives
 * in Link / dispatcher so it can be tested without hardware.
 *
 * @param {object} opts
 * @param {string} opts.deviceName
 * @param {() => Buffer} opts.deviceInfo  Device Info characteristic value
 * @param {() => Buffer} opts.readStatus  current Status value
 * @param {(t: {notify: Function, maxFragment: () => number}) => Link} opts.makeLink
 * @param {(link: Link) => void} opts.onConnect
 * @param {(link: Link) => void} opts.onDisconnect
 * @param {boolean} [opts.requireEncryption]
 * @param {Logger} opts.logger
 */
export const startGattServer = ({
  deviceName,
  deviceInfo,
  readStatus,
  makeLink,
  onConnect,
  onDisconnect,
  requireEncryption = true,
  logger,
}) =>
  new Promise((resolve, reject) => {
    const transport = new HciSocket()

    BleManager.create(transport, {}, (err, manager) => {
      if (err) return reject(err)

      const links = new Map() // connection -> Link
      const perm = requireEncryption ? 'encrypted' : 'open'

      const statusChar = {
        uuid: UUID.status,
        properties: ['read', 'notify'],
        onRead: (connection, cb) => cb(AttErrors.SUCCESS, readStatus()),
      }
      const responseChar = {
        uuid: UUID.response,
        properties: ['notify'],
        onSubscriptionChange: () => {},
      }
      const commandChar = {
        uuid: UUID.command,
        properties: ['write', 'write-without-response'],
        writePerm: perm,
        onWrite: (connection, needsResponse, value, cb) => {
          links.get(connection)?.onWrite(value)
          if (needsResponse) cb(AttErrors.SUCCESS)
        },
      }

      manager.gattDb.setDeviceName(deviceName)
      manager.gattDb.addServices([
        {
          uuid: UUID.service,
          characteristics: [
            {
              uuid: UUID.deviceInfo,
              properties: ['read'],
              onRead: (connection, cb) => cb(AttErrors.SUCCESS, deviceInfo()),
            },
            statusChar,
            commandChar,
            responseChar,
          ],
        },
      ])

      const adv = new AdvertisingDataBuilder()
        .addFlags(['leGeneralDiscoverableMode', 'brEdrNotSupported'])
        .add128BitServiceUUIDs(true, [UUID.service])
        .build()
      const complete = Buffer.byteLength(deviceName) <= MAX_ADV_NAME
      const scanRsp = new AdvertisingDataBuilder()
        .addLocalName(complete, deviceName.slice(0, MAX_ADV_NAME))
        .build()
      manager.setAdvertisingData(adv)
      manager.setScanResponseData(scanRsp)

      const startAdv = () => {
        manager.startAdvertising({ intervalMin: 800, intervalMax: 1000 }, onConn)
      }

      function onConn(status, conn) {
        if (status !== HciErrors.SUCCESS) {
          logger.log('ble', `Advertising failed (${status}); retrying in 10 s`)
          setTimeout(startAdv, 10_000)
          return
        }

        const link = makeLink({
          notify: (frag, done) => responseChar.notify(conn, frag, done),
          maxFragment: () => conn.gatt.currentMtu - 3,
        })
        links.set(conn, link)
        logger.log('ble', `Connected ${conn.peerAddress}`)
        onConnect(link)

        conn.on('disconnect', () => {
          links.delete(conn)
          link.close()
          onDisconnect(link)
          logger.log('ble', `Disconnected ${conn.peerAddress}`)
          startAdv()
        })
      }

      startAdv()
      logger.log('ble', `Advertising as '${deviceName}'`)

      resolve({
        notifyStatus: (buf) => statusChar.notifyAll(buf),
        close: () => transport.close?.(),
      })
    })
  })
