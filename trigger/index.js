import { resolve } from 'path'

import { readJson } from './lib/utils.js'
import { Logger } from './lib/logger.js'

import { Scheduler } from './lib/scheduler.js'
import { initBle } from './ble/index.js'
import { initController } from './controller.js'

const configFile = resolve(process.env.CAMERA_CONFIG_FILE ?? './config.json')
const configJson = await readJson(configFile)

const logger = new Logger({ logFile: configJson.logFile })

const scheduler = new Scheduler({ logger })

scheduler.initialize(configJson)

const controller = initController({ configFile, scheduler, logger })

const ble = initBle({ configFile, scheduler, controller, logger })

const shutdown = () => {
  ble.stop()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
