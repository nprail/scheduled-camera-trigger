import HciSocket from 'hci-socket'
import NodeBleHost from 'ble-host'
import {
  CONFIG_READ_CHARACTERISTIC_UUID,
  CONFIG_WRITE_CHARACTERISTIC_UUID,
  RUN_TEST_CHARACTERISTIC_UUID,
  SERVICE_UUID,
  getConfigValueByIndex,
} from './lib/config-ble-index.js'

const { BleManager, HciErrors, AttErrors, AdvertisingDataBuilder } = NodeBleHost

export const initBluetooth = ({
  configFile,
  scheduler,
  controller,
  logger,
}) => {
  const transport = new HciSocket() // connects to the first hci device on the computer, for example hci0

  const options = {
    // optional properties go here
  }

  BleManager.create(transport, options, (err, manager) => {
    // err is either null or an Error object
    // if err is null, manager contains a fully initialized BleManager object
    if (err) {
      logger.log('bluetooth', err?.message ?? err)
      return
    }

    const deviceName = scheduler.config.name

    manager.gattDb.setDeviceName(scheduler.config.deviceName)
    manager.gattDb.addServices([
      {
        uuid: SERVICE_UUID,
        characteristics: [
          {
            uuid: CONFIG_READ_CHARACTERISTIC_UUID, // read config by index
            properties: ['write'],
            onWrite: function (connection, needsResponse, value, callback) {
              const stringValue = value.toString('utf8')
              logger.log('bluetooth', 'read config', {
                needsResponse,
                value,
                stringValue,
              })

              const requestData = stringValue.split(',')

              let data = getConfigValueByIndex(
                scheduler.config,
                requestData[0] /* item index */,
                requestData[1] /* array index */
              )

              if (Array.isArray(data)) {
                data = data.length
              }

              const responseBuffer = Buffer.from(
                `${stringValue},${data}`,
                'utf-8'
              )
              callback(AttErrors.SUCCESS, responseBuffer)
            },
          },
          {
            uuid: CONFIG_WRITE_CHARACTERISTIC_UUID, // write config
            properties: ['read'],
            onWrite: function (connection, needsResponse, value, callback) {
              logger.log('bluetooth', 'write config', { needsResponse, value })
              callback(AttErrors.SUCCESS, new Date().toString())
            },
          },
          {
            uuid: RUN_TEST_CHARACTERISTIC_UUID, // run test
            properties: ['write'],
            onWrite: function (connection, needsResponse, value, callback) {
              logger.log('bluetooth', 'run test', { needsResponse, value })
              controller
                .test()
                .then(() => {
                  callback(AttErrors.SUCCESS) // actually only needs to be called when needsResponse is true
                })
                .catch((err) => {
                  callback(AttErrors.UNLIKELY_ERROR)
                })
            },
          },
        ],
      },
    ])

    const advDataBuffer = new AdvertisingDataBuilder()
      .addFlags(['leGeneralDiscoverableMode', 'brEdrNotSupported'])
      .addLocalName(/*isComplete*/ true, deviceName)
      .add128BitServiceUUIDs(/*isComplete*/ true, [SERVICE_UUID])
      .build()
    manager.setAdvertisingData(advDataBuffer)
    // call manager.setScanResponseData(...) if scan response data is desired too
    startAdv()
    logger.log('bluetooth', 'Initialized')

    function startAdv() {
      manager.startAdvertising({}, (status, conn) => {
        if (status != HciErrors.SUCCESS) {
          // Advertising could not be started for some controller-specific reason, try again after 10 seconds
          setTimeout(startAdv, 10000)
          return
        }
        conn.on('disconnect', startAdv) // restart advertising after disconnect
        logger.log('bluetooth', 'Connection established!')
      })
    }
  })
}
