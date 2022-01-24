import schedule from 'node-schedule'
import ms from 'ms'

import { Camera } from './camera.js'
import { log, readJson, sleep } from './utils.js'

const cam = new Camera()

const attempts = await readJson('./schedule.json')

const jobs = attempts.map((attempt) => {
  const launchTime = new Date(attempt.date)

  // start the job 31 seconds before ignition to allow for 30 seconds of recording before
  const jobTime = new Date(launchTime - ms('31s'))

  schedule.scheduleJob(jobTime, async () => {
    console.log('')
    log(`Starting '${attempt.name}'`)

    // wake up the camera
    cam.wake()

    await sleep('1s')

    // press the trigger to start recording
    cam.trigger()

    // wait 1 minute
    await sleep('30s')

    log(`Ignition ${launchTime.toISOString()}`)

    await sleep('30s')

    // press the trigger again to stop recording
    cam.trigger()

    log(`'${attempt.name}' completed.`)
  })
})

console.log(`${jobs.length} attempts scheduled...`)
