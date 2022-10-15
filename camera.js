import { Gpio } from 'onoff'

export class Camera {
  constructor({ logger, focusGpioPort, releaseGpioPort }) {
    this.logger = logger

    try {
      this.release = new Gpio(releaseGpioPort, 'out')

      if (focusGpioPort) {
        this.focus = new Gpio(opts.focusGpioPort, 'out')
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
