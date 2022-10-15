import { log } from './utils.js'
import { Gpio } from 'onoff'

export class Camera {
  constructor(opts) {
    this.release = new Gpio(opts.releaseGpioPort, 'out')

    if (opts.focusGpioPort) {
      this.focus = new Gpio(opts.focusGpioPort, 'out')
    } else {
      this.focus = this.release
    }


    this.recording = false
  }

  async wake() {
    log('Wake')
    await this.focus.write(1)
    await this.focus.write(0)
  }

  async trigger() {
    if (!this.recording) {
      log('Record')
    } else {
      log('Stop')
    }

    await this.release.write(1)
    await this.release.write(0)

    this.recording = !this.recording
  }
}
