import schedule from 'node-schedule'
import ms from 'ms'

import { Camera } from './camera.js'
import { log, readJson } from './utils.js'

const cam = new Camera()

const attempts = await readJson('./schedule.json')

const jobs = attempts.map((attempt) => {
  const launchTime = new Date(attempt.date)

  // start recording 20 seconds before ignition
  const recordTime = new Date(launchTime.getTime() - ms('20s'))
  // wake up the camera 5 seconds before recording
  const wakeUpTime = new Date(recordTime.getTime() - ms('5s'))
  // stop recording 30 seconds after ignition
  const stopTime = new Date(launchTime.getTime() + ms('30s'))

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
