import HciSocket from 'hci-socket'
import NodeBleHost from 'ble-host'

const { BleManager, HciErrors, AttErrors, AdvertisingDataBuilder } = NodeBleHost

export const initBluetooth = ({ configFile, scheduler, logger }) => {
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

    const deviceName = scheduler.config.deviceName

    // let notificationCharacteristic

    const serviceUuid = '530c35d9-866f-4fb1-87f6-11c0f598ea84'

    manager.gattDb.setDeviceName(deviceName)
    manager.gattDb.addServices([
      {
        uuid: serviceUuid,
        characteristics: [
          {
            uuid: '90b4137b-88d7-4b15-a48b-13f9077a487e', // config
            properties: ['read', 'write'],
            onRead: function (connection, callback) {
              logger.log('bluetooth', 'read config')
              callback(AttErrors.SUCCESS, new Date().toString())
            },
            onWrite: function (connection, needsResponse, value, callback) {
              logger.log('bluetooth', 'write config', { needsResponse, value })
              callback(AttErrors.SUCCESS)
            },
          },
          {
            uuid: '84425c54-3368-43f6-933c-ef2fba2b1a52', // read logs
            properties: ['read'],
            onRead: function (connection, callback) {
              logger.log('bluetooth', 'read logs')
              callback(AttErrors.SUCCESS, new Date().toString())
            },
          },
          {
            uuid: 'e7a9479e-cec7-423c-8338-69f55fc04b5f', // run test
            properties: ['write'],
            onWrite: function (connection, needsResponse, value, callback) {
              logger.log('bluetooth', 'run test', { needsResponse, value })
              callback(AttErrors.SUCCESS) // actually only needs to be called when needsResponse is true
            },
          },
        ],
      },
    ])

    const advDataBuffer = new AdvertisingDataBuilder()
      .addFlags(['leGeneralDiscoverableMode', 'brEdrNotSupported'])
      .addLocalName(/*isComplete*/ true, deviceName)
      .add128BitServiceUUIDs(/*isComplete*/ true, [serviceUuid])
      .build()
    manager.setAdvertisingData(advDataBuffer)
    // call manager.setScanResponseData(...) if scan response data is desired too
    startAdv()

    function startAdv() {
      manager.startAdvertising({}, (status, conn) => {
        if (status != HciErrors.SUCCESS) {
          // Advertising could not be started for some controller-specific reason, try again after 10 seconds
          setTimeout(startAdv, 10000)
          return
        }
        conn.on('disconnect', startAdv) // restart advertising after disconnect
        logger.log('bluetooth', 'Connection established!', conn)
      })
    }
  })
}
