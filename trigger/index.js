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

initServer({ configFile, scheduler, logger })
initButton({ config: configJson, logger })
