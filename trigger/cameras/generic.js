import { Gpio } from 'onoff'
import { BaseCamera } from './base.js'

export class Generic extends BaseCamera {
  constructor(opts) {
    super(opts)
    this.logger = opts.logger
    this.config = opts.config

    if (!opts.config.generic || !opts.config.generic.releaseGpioPort) {
      throw new Error('Generic camera configuration not set properly')
    }

    try {
      this.release = new Gpio(opts.config.generic.releaseGpioPort, 'out')

      if (opts.config.generic.focusGpioPort) {
        this.focus = new Gpio(opts.config.generic.focusGpioPort, 'out')
      } else {
        this.focus = this.release
      }

      this.recording = false
    } catch (err) {
      this.logger.log('Generic', err?.message ?? err, err)
    }
  }

  logError(err) {
    this.logger.log('Generic', err?.message ?? err, err)
  }

  async wake() {
    try {
      this.logger.log('Generic', 'Wake')
      await this.focus.write(1)
      await this.focus.write(0)
    } catch (err) {
      this.logError(err)
    }
  }

  async start() {
    try {
      if (this.recording) return

      return await this.trigger()
    } catch (err) {
      this.logError(err)
    }
  }

  async stop() {
    try {
      if (!this.recording) return

      return await this.trigger()
    } catch (err) {
      this.logError(err)
    }
  }

  async trigger() {
    try {
      this.logger.log('Generic', this.recording ? 'Stop' : 'Record')

      await this.release.write(1)
      await this.release.write(0)

      this.recording = !this.recording
    } catch (err) {
      this.logError(err)
    }
  }
}
