import schedule from 'node-schedule'
import ms from 'ms'
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
    const Camera = cameras[this.config.camera]
    this.cam = new Camera({
      logger: this.logger,
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
        this.logger.log('index', `Start '${attempt.name}'`)

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

        this.logger.log('index', `Complete '${attempt.name}'`)

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

    this.logger.log(
      'index',
      `${this.config.attempts.length} attempts scheduled`,
      this.jobs
    )
  }
}
