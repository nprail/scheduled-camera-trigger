import schedule from 'node-schedule'
import ms from 'ms'

import { Camera } from './camera.js'
import { log, readJson } from './utils.js'

const cam = new Camera()

const config = await readJson('./config.json')

const jobs = config.attempts.map((attempt) => {
  const launchTime = new Date(attempt.time)

  // start recording x seconds before ignition
  const recordTime = new Date(launchTime.getTime() - ms(config.startBefore))
  // wake up the camera x seconds before recording
  const wakeUpTime = new Date(recordTime.getTime() - ms(config.wakeUpTimeout))
  // stop recording x seconds after ignition
  const stopTime = new Date(launchTime.getTime() + ms(config.endAfter))

  // wake up the camera
  schedule.scheduleJob(wakeUpTime, () => {
    console.log('')
    log(`Start '${attempt.name}'`)

    cam.wake()
  })

  // press the trigger to start recording
  schedule.scheduleJob(recordTime, () => {
    cam.trigger()
  })

  // just log some stuff
  schedule.scheduleJob(launchTime, () => {
    log(`Ignition ${launchTime.toISOString()}`)
  })

  // press the trigger again to stop recording
  schedule.scheduleJob(stopTime, () => {
    cam.trigger()

    log(`Complete '${attempt.name}'`)
  })
})

console.log(`${jobs.length} attempts scheduled...`)
