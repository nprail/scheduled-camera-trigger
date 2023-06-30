import { resolve } from 'path'

import { readJson } from './lib/utils.js'
import { Logger } from './lib/logger.js'

import { Scheduler } from './lib/scheduler.js'
import { initServer } from './server.js'
import { initButton } from './button.js'

const configFile = resolve(process.env.CAMERA_CONFIG_FILE ?? './config.json')
const configJson = await readJson(configFile)

const logger = new Logger({ logFile: configJson.logFile })

const scheduler = new Scheduler({ logger })

scheduler.initialize(configJson)

const { server, bonjour } = initServer({ configFile, scheduler, logger })
const button = initButton({ config: configJson, logger })

const shutdown = () => {
  bonjour.unpublishAll()

  if (button) button.unexport()

  server.close(() => {
    bonjour.destroy()
  })
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
