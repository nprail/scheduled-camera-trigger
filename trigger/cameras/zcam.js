import { ZCamApi } from '../lib/zcam-api.js'
import { BaseCamera } from './base.js'

export class ZCam extends BaseCamera {
  constructor(opts) {
    super(opts)

    if (!opts.config.zcam || !opts.config.zcam.cameraIp) {
      throw new Error('ZCam camera configuration not set properly')
    }

    this.zcam = new ZCamApi(`http://${this.config.zcam.cameraIp}`)
  }

  logError(err) {
    this.logger.log('ZCam', err?.message ?? err, err)
  }

  async setup() {
    try {
      const res = await this.zcam.acquireSession()

      if (res?.code !== 0) {
        throw new Error('ZCam', 'failed to connect')
      }
    } catch (err) {
      this.logError(err)
    }
  }

  async wake() {
    try {
      await this.setup()
      this.logger.log('ZCam', 'Wake')
    } catch (err) {
      this.logError(err)
    }
  }

  async sleep() {
    try {
      this.logger.log('ZCam', 'Sleep')
      await this.zcam.mode('to_standby')
    } catch (err) {
      this.logError(err)
    }
  }

  async start() {
    try {
      const time = await this.zcam.recordRemain()

      this.logger.log('ZCam', 'Start Recording', time)

      await this.zcam.recordStart()
      this.recording = true
    } catch (err) {
      this.logError(err)
    }
  }

  async stop() {
    try {
      await this.zcam.recordStop()
      this.recording = false

      const time = await this.zcam.recordRemain()

      this.logger.log('ZCam', 'Stop Recording', time)
    } catch (err) {
      this.logError(err)
    }
  }
}
