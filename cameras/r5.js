import { Gpio } from 'onoff'
import { BaseCamera } from './base.js'

export class R5 extends BaseCamera {
  constructor(opts) {
    super(opts)
    this.logger = opts.logger
    this.config = opts.config

    try {
      this.release = new Gpio(opts.config.releaseGpioPort, 'out')

      if (opts.config.focusGpioPort) {
        this.focus = new Gpio(opts.config.focusGpioPort, 'out')
      } else {
        this.focus = this.release
      }

      this.recording = false
    } catch (err) {
      this.logger.log(err)
    }
  }

  async wake() {
    try {
      this.logger.log('Wake')
      await this.focus.write(1)
      await this.focus.write(0)
    } catch (err) {
      this.logger.log(err)
    }
  }

  async start() {
    if (this.recording) return

    return await this.trigger()
  }

  async stop() {
    if (!this.recording) return

    return await this.trigger()
  }

  async trigger() {
    try {
      this.logger.log(this.recording ? 'Stop' : 'Record')

      await this.release.write(1)
      await this.release.write(0)

      this.recording = !this.recording
    } catch (err) {
      this.logger.log(err)
    }
  }
}
