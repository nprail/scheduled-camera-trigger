import { ZCamApi } from '../lib/zcam-api.js'
import { BaseCamera } from './base.js'

export class ZCam extends BaseCamera {
  constructor(opts) {
    super(opts)

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
      this.logger.log('ZCam', 'Start Recording')
      const time = await this.zcam.recordRemain()

      this.logger.log(
        'ZCam',
        `${time?.minutes} minutes of recording space remaining`
      )

      await this.zcam.recordStart()
    } catch (err) {
      this.logError(err)
    }
  }

  async stop() {
    try {
      this.logger.log('ZCam', 'Stop Recording')
      await this.zcam.recordStop()

      const time = await this.zcam.recordRemain()

      this.logger.log(
        'ZCam',
        `${time?.minutes} minutes of recording space remaining`
      )
    } catch (err) {
      this.logError(err)
    }
  }
}
