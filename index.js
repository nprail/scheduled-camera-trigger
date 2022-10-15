import schedule from 'node-schedule'
import ms from 'ms'
import { v4 as uuid } from 'uuid'

import { Camera } from './camera.js'
import { log, readJson } from './utils.js'

const config = await readJson('./config.json')

const cam = new Camera({
  releaseGpioPort: config.releaseGpioPort,
  focusGpioPort: config.focusGpioPort,
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
    log(`Start '${attempt.name}'`)

    cam.wake()
  })

  // press the trigger to start recording
  const triggerJob = schedule.scheduleJob(recordTime, () => {
    cam.trigger()
  })

  // press the trigger again to stop recording
  const stopJob = schedule.scheduleJob(stopTime, () => {
    cam.trigger()

    log(`Complete '${attempt.name}'`)
  })

  return {
    id: uuid(),
    jobs: [wakeUpJob, triggerJob, stopJob],
  }
})

console.log(jobs)

console.log(`${config.attempts.length} attempts scheduled...`)
