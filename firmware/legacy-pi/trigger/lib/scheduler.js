import { EventEmitter } from 'events'
import nodeSchedule from 'node-schedule'
import { v4 as uuid } from 'uuid'

/**
 * Schedules wake / record / stop for each launch attempt.
 *
 * Emits:
 *  - 'job'    { name, index, step: 'wake'|'start'|'stop'|'complete'|'missed', at }
 *  - 'change' (any state a status consumer cares about changed)
 *  - 'test'   { step: 'wake'|'start'|'stop'|'sleep'|'done', ok?, error? }
 */
export class Scheduler extends EventEmitter {
  /**
   * @param {object} opts
   * @param {Logger} opts.logger
   * @param {Record<string, new (o: object) => BaseCamera>} opts.cameras
   * @param {Function} [opts.scheduleJob] defaults to node-schedule's
   * @param {Function} [opts.now] epoch ms clock
   */
  constructor({
    logger,
    cameras,
    scheduleJob = nodeSchedule.scheduleJob,
    now = () => Date.now(),
  }) {
    super()
    this.schedules = []
    this.jobs = []
    this.logger = logger
    this.cameras = cameras
    this.scheduleJob = scheduleJob
    this.now = now
    this.testing = false
  }

  /** 'idle' | 'awake' | 'recording' | 'test' */
  get phase() {
    if (this.testing) return 'test'
    const active = this.jobs.find((j) => j.state === 'active')
    if (active) return active.recording ? 'recording' : 'awake'
    if (this.cam?.recording) return 'recording'
    return 'idle'
  }

  /** True while a scheduled attempt or a test owns the camera. */
  get locked() {
    return this.testing || this.jobs.some((j) => j.state === 'active')
  }

  /** Next future step across all unfinished jobs, or null. */
  get next() {
    const now = this.now()
    let best = null
    for (const [index, job] of this.jobs.entries()) {
      if (job.state === 'complete' || job.state === 'missed') continue
      for (const [type, key] of [
        ['wake', 'wakeUpTime'],
        ['start', 'recordTime'],
        ['stop', 'stopTime'],
      ]) {
        const at = job.config[key]
        if (at.getTime() > now && (!best || at < best.at)) {
          best = { type, at, attempt: job.name, index }
        }
      }
    }
    return best
  }

  get attemptsRemaining() {
    return this.jobs.filter(
      (j) => j.state === 'pending' || j.state === 'active'
    ).length
  }

  teardown() {
    this.logger.log('index', 'TEARDOWN')

    for (const schedule of this.schedules) {
      schedule?.cancel()
    }

    this.schedules = []
    this.jobs = []

    try {
      this.cam?.dispose()
    } catch (err) {
      this.logger.log('index', `Camera dispose failed: ${err?.message ?? err}`)
    }
  }

  /** Re-read the clock: cancel everything and schedule it again. */
  reinitialize() {
    const config = this.config
    this.teardown()
    this.initialize(config)
  }

  initialize(config) {
    this.config = config
    this.logger.log('index', 'INITIALIZE')

    const Camera = this.cameras[this.config.cameraType]
    try {
      this.cam = new Camera({
        logger: this.logger,
        config: this.config,
      })
    } catch (err) {
      this.logger.log('index', `Camera init failed: ${err?.message ?? err}`)
      this.cam = undefined
    }

    const startBefore = this.config.startBefore * 1000
    const wakeUpTimeout = this.config.wakeUpTimeout * 1000
    const endAfter = this.config.endAfter * 1000
    const now = this.now()

    this.jobs = this.config.attempts.map((attempt, index) => {
      const name = attempt.name
      const launchTime = new Date(attempt.time)
      // start recording x seconds before ignition
      const recordTime = new Date(launchTime.getTime() - startBefore)
      // wake up the camera x seconds before recording
      const wakeUpTime = new Date(recordTime.getTime() - wakeUpTimeout)
      // stop recording x seconds after ignition
      const stopTime = new Date(launchTime.getTime() + endAfter)

      const job = {
        id: uuid(),
        name,
        camera: this.config.cameraType,
        state: 'pending',
        recording: false,
        config: { wakeUpTime, launchTime, recordTime, stopTime },
      }

      // Anything whose wake time has already passed can't run in full.
      if (wakeUpTime.getTime() <= now) {
        job.state = 'missed'
        this.emit('job', { name, index, step: 'missed', at: new Date(now) })
        return job
      }

      const step = (step, fn) => async () => {
        try {
          await fn()
        } catch (err) {
          this.logger.log('index', `${step} failed: ${err?.message ?? err}`)
        }
        this.emit('change')
      }

      // wake up the camera
      this.#add(
        wakeUpTime,
        step('wake', async () => {
          this.logger.log('index', `Start '${name}'`)
          job.state = 'active'
          this.emit('job', { name, index, step: 'wake', at: new Date(this.now()) })
          this.emit('change')
          await this.cam?.wake()
        })
      )

      // press the trigger to start recording
      this.#add(
        recordTime,
        step('start', async () => {
          await this.cam?.start()
          job.recording = true
          this.emit('job', { name, index, step: 'start', at: new Date(this.now()) })
        })
      )

      // press the trigger again to stop recording
      this.#add(
        stopTime,
        step('stop', async () => {
          await this.cam?.stop()
          job.recording = false
          this.emit('job', { name, index, step: 'stop', at: new Date(this.now()) })

          this.logger.log('index', `Complete '${name}'`)

          await this.cam?.sleep()
          job.state = 'complete'
          this.emit('job', { name, index, step: 'complete', at: new Date(this.now()) })
        })
      )

      return job
    })

    this.logger.log(
      'index',
      `${this.config.attempts.length} attempts scheduled`,
      this.jobs
    )
    this.emit('change')
  }

  #add(date, fn) {
    const handle = this.scheduleJob(date, fn)
    if (handle) this.schedules.push(handle)
  }

  /** Wake -> record 2s -> stop -> sleep, reporting progress as 'test' events. */
  async runTest() {
    if (this.locked) {
      throw Object.assign(new Error('Camera is busy'), { code: 'busy' })
    }
    this.testing = true
    this.emit('change')

    const pause = (ms) => new Promise((r) => setTimeout(r, ms))
    const emit = (step, extra = {}) => this.emit('test', { step, ...extra })

    try {
      emit('wake')
      await this.cam.wake()
      await pause(1000)
      emit('start')
      await this.cam.start()
      await pause(2000)
      emit('stop')
      await this.cam.stop()
      emit('sleep')
      await this.cam.sleep()
      emit('done', { ok: true })
    } catch (err) {
      emit('done', { ok: false, error: err?.message ?? String(err) })
    } finally {
      this.testing = false
      this.emit('change')
    }
  }
}
