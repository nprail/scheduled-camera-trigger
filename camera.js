import { Gpio } from 'onoff'

export class Camera {
  constructor({ logger }) {
    this.release = new Gpio(17, 'out')
    this.focus = new Gpio(18, 'out')

    this.recording = false
  }

  async wake() {
    logger.log('Wake')
    await this.focus.write(1)
    await this.focus.write(0)
  }

  async trigger() {
    if (!this.recording) {
      logger.log('Record')
    } else {
      logger.log('Stop')
    }

    await this.release.write(1)
    await this.release.write(0)

    this.recording = !this.recording
  }
}
