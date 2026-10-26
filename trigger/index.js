import { resolve } from 'path'

import { readJson } from './lib/utils.js'
import { Logger } from './lib/logger.js'
import { System } from './lib/system.js'
import { cameras } from './cameras/index.js'

import { Scheduler } from './lib/scheduler.js'
import { initBle } from './ble/index.js'
import { initController } from './controller.js'

const configFile = resolve(process.env.CAMERA_CONFIG_FILE ?? './config.json')
const configJson = await readJson(configFile)

const logger = new Logger({ logFile: configJson.logFile })
const system = new System()

// The device is controlled over Bluetooth only; keep the Wi-Fi radio off.
system.disableWifi().catch((err) => {
  logger.log('index', `Could not block Wi-Fi: ${err?.message ?? err}`)
})

const scheduler = new Scheduler({ logger, cameras })

scheduler.initialize(configJson)

const controller = initController({ configFile, scheduler, logger, system })

// The schedule must keep running even if Bluetooth can't start.
initBle({ scheduler, controller, logger, system }).catch((err) => {
  logger.log('ble', `Failed to start: ${err?.message ?? err}`)
})

const shutdown = () => {
  scheduler.teardown()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
