import schedule from 'node-schedule'
import ms from 'ms'
import { v4 as uuid } from 'uuid'
import { resolve } from 'path'

import { R5 } from './cameras/r5.js'
import { ZCam } from './cameras/zcam.js'

import { Logger, readJson } from './utils.js'
import { initServer } from './server.js'

const configFile = resolve(process.env.CAMERA_CONFIG_FILE ?? './config.json')
const configJson = await readJson(configFile)

const cameras = {
  r5: R5,
  zcam: ZCam,
}

const logger = new Logger({ logFile: configJson.logFile })

class Scheduler {
  constructor() {
    this.schedules = []
  }

  teardown() {
    logger.log('index', 'TEARDOWN')

    for (const schedule of this.schedules) {
      schedule?.cancel()
    }

    this.schedules = []
  }

  initialize(config) {
    this.config = config
    logger.log('index', 'INITIALIZE')
    const Camera = cameras[this.config.camera]
    this.cam = new Camera({
      logger,
      config: this.config,
    })

    this.jobs = this.config.attempts.map((attempt) => {
      const launchTime = new Date(attempt.time)

      // start recording x seconds before ignition
      const recordTime = new Date(
        launchTime.getTime() - ms(this.config.startBefore)
      )
      // wake up the camera x seconds before recording
      const wakeUpTime = new Date(
        recordTime.getTime() - ms(this.config.wakeUpTimeout)
      )
      // stop recording x seconds after ignition
      const stopTime = new Date(launchTime.getTime() + ms(this.config.endAfter))

      // wake up the camera
      const wakeUpJob = schedule.scheduleJob(wakeUpTime, () => {
        logger.log('index', `Start '${attempt.name}'`)

        this.cam.wake()
      })
      this.schedules.push(wakeUpJob)

      // press the trigger to start recording
      const triggerJob = schedule.scheduleJob(recordTime, () => {
        this.cam.start()
      })
      this.schedules.push(triggerJob)

      // press the trigger again to stop recording
      const stopJob = schedule.scheduleJob(stopTime, async () => {
        await this.cam.stop()

        logger.log('index', `Complete '${attempt.name}'`)

        await this.cam.sleep()
      })
      this.schedules.push(stopJob)

      return {
        id: uuid(),
        name: attempt.name,
        camera: this.config.camera,
        config: {
          wakeUpTime,
          launchTime,
          recordTime,
          stopTime,
        },
      }
    })

    logger.log(
      'index',
      `${this.config.attempts.length} attempts scheduled`,
      this.jobs
    )
  }
}

const scheduler = new Scheduler()

scheduler.initialize(configJson)

initServer({ configFile, scheduler, logger })
