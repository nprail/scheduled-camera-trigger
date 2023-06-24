import { ZCamApi } from '../lib/zcam-api.js'
import { BaseCamera } from './base.js'

export class ZCam extends BaseCamera {
  constructor(opts) {
    super(opts)

    this.zcam = new ZCamApi(`http://${this.config.zcam.cameraIp}`)
  }

  async setup() {
    const res = await this.zcam.acquireSession()

    if (res?.code !== 0) {
      throw new Error('ZCam failed to connect')
    }
  }

  async wake() {
    await this.setup()
    this.logger.log('ZCam Wake')
  }

  async sleep() {
    this.logger.log('ZCam Sleep')
    await this.zcam.mode('to_standby')
  }

  async start() {
    this.logger.log('ZCam Start Recording')
    const time = await this.zcam.recordRemain()

    this.logger.log(
      `ZCam ${time?.minutes} minutes of recording space remaining`
    )

    await this.zcam.recordStart()
  }

  async stop() {
    this.logger.log('ZCam Stop Recording')
    await this.zcam.recordStop()

    const time = await this.zcam.recordRemain()

    this.logger.log(
      `ZCam ${time?.minutes} minutes of recording space remaining`
    )
  }
}
