import { log } from './utils.js'

export class Camera {
  constructor() {
    this.recording = false
  }

  async wake() {
    log('Wake')
  }

  async trigger() {
    if (!this.recording) {
      log('Record')
    } else {
      log('Stop')
    }

    this.recording = !this.recording
  }
}
