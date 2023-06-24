import schedule from 'node-schedule'
import ms from 'ms'
import { v4 as uuid } from 'uuid'

import { R5 } from './cameras/r5.js'
import { ZCam } from './cameras/zcam.js'

import { Logger, readJson } from './utils.js'
import { initServer } from './server.js'

const configFile = process.env.CAMERA_CONFIG_FILE
const config = await readJson(configFile ?? './config.json')

const cameras = {
  r5: R5,
  zcam: ZCam,
}

const logger = new Logger({ logFile: config.logFile })

const Camera = cameras[config.camera]
const cam = new Camera({
  logger,
  config,
})

const jobs = config.attempts.map((attempt) => {
  const launchTime = new Date(attempt.time)

  // start recording x seconds before ignition
  const recordTime = new Date(launchTime.getTime() - ms(config.startBefore))
  // wake up the camera x seconds before recording
  const wakeUpTime = new Date(recordTime.getTime() - ms(config.wakeUpTimeout))
  // stop recording x seconds after ignition
  const stopTime = new Date(launchTime.getTime() + ms(config.endAfter))

  // wake up the camera
  const wakeUpJob = schedule.scheduleJob(wakeUpTime, () => {
    console.log('')
    logger.log(`Start '${attempt.name}'`)

    cam.wake()
  })

  // press the trigger to start recording
  const triggerJob = schedule.scheduleJob(recordTime, () => {
    cam.start()
  })

  // press the trigger again to stop recording
  const stopJob = schedule.scheduleJob(stopTime, async () => {
    await cam.stop()

    logger.log(`Complete '${attempt.name}'`)

    await cam.sleep()
  })

  return {
    id: uuid(),
    camera: config.camera,
    config: {
      wakeUpTime,
      launchTime,
      recordTime,
      stopTime,
    },
  }
})

logger.log(
  `${
    config.attempts.length
  } attempts scheduled at ${new Date().toISOString()}...`,
  jobs
)

initServer(config, jobs, cam, logger)
