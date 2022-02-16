import { Gpio } from 'onoff'

export class Camera {
  constructor({ logger }) {
    this.logger = logger

    try {
      this.release = new Gpio(17, 'out')
      this.focus = new Gpio(18, 'out')

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
