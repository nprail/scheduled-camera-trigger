import { Gpio } from 'onoff'
import { BaseCamera } from './base.js'

export class Generic extends BaseCamera {
  constructor(opts) {
    super(opts)
    this.logger = opts.logger
    this.config = opts.config

    if (opts.config.generic?.releaseGpioPin == null) {
      throw new Error('Generic camera configuration not set properly')
    }

    this.ready = false

    try {
      this.release = new Gpio(opts.config.generic.releaseGpioPin, 'out')

      if (opts.config.generic.focusGpioPin != null) {
        this.focus = new Gpio(opts.config.generic.focusGpioPin, 'out')
      } else {
        this.focus = this.release
      }

      this.recording = false
      this.ready = true
    } catch (err) {
      this.logger.log('Generic', err?.message ?? err, err)
    }
  }

  dispose() {
    try {
      if (this.focus && this.focus !== this.release) this.focus.unexport()
      this.release?.unexport()
    } catch (err) {
      this.logError(err)
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
