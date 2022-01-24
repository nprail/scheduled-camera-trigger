export class Camera {
  constructor() {
    this.recording = false
  }

  log(message, ...optionalParams) {
    console.log(`${new Date().toISOString()} ${message}`, ...optionalParams)
  }

  async wake() {
    this.log('Waking up camera.')
  }

  async trigger() {
    if (!this.recording) {
      this.log('Starting recording.')
    } else {
      this.log('Ending recording.')
    }

    this.recording = !this.recording
  }
}
