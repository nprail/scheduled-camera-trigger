import schedule from 'node-schedule'
import { v4 as uuid } from 'uuid'

import { Generic } from '../cameras/generic.js'
import { ZCam } from '../cameras/zcam.js'

const cameras = {
  generic: Generic,
  zcam: ZCam,
}

export class Scheduler {
  constructor({ logger }) {
    this.schedules = []
    this.logger = logger
  }

  teardown() {
    this.logger.log('index', 'TEARDOWN')

    for (const schedule of this.schedules) {
      schedule?.cancel()
    }

    this.schedules = []
  }

  initialize(config) {
    this.config = config
    this.logger.log('index', 'INITIALIZE')
    const Camera = cameras[this.config.cameraType]
    this.cam = new Camera({
      logger: this.logger,
      config: this.config,
    })

    this.jobs = this.config.attempts.map((attempt) => {
      const launchTime = new Date(attempt)

      // start recording x seconds before ignition
      const recordTime = new Date(
        launchTime.getTime() - this.config.startBefore * 1000
      )
      // wake up the camera x seconds before recording
      const wakeUpTime = new Date(
        recordTime.getTime() - this.config.wakeUpTimeout * 1000
      )
      // stop recording x seconds after ignition
      const stopTime = new Date(
        launchTime.getTime() + this.config.endAfter * 1000
      )

      // wake up the camera
      const wakeUpJob = schedule.scheduleJob(wakeUpTime, () => {
        this.logger.log('index', `Start '${attempt}'`)

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

        this.logger.log('index', `Complete '${attempt}'`)

        await this.cam.sleep()
      })
      this.schedules.push(stopJob)

      return {
        id: uuid(),
        camera: this.config.cameraType,
        config: {
          wakeUpTime,
          launchTime,
          recordTime,
          stopTime,
        },
      }
    })

    this.logger.log(
      'index',
      `${this.config.attempts.length} attempts scheduled`,
      this.jobs
    )
  }
}
