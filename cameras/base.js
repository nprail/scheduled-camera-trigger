export class BaseCamera {
  constructor({ logger, config }) {
    this.logger = logger
    this.config = config
    this.recording = false
  }

  /**
   * Start recording
   */
  async start() {}

  /**
   * Stop recording
   */
  async stop() {}

  /**
   * Wake the camera up
   */
  async wake() {}

  /**
   * Put the camera to sleep
   */
  async sleep() {}

  /**
   * Pause for specified time
   * @param {number} time Time to pause in ms
   * 
   * @private 
   */
  pause(time) {
    return new Promise((resolve) => {
      setTimeout(() => resolve(), time)
    })
  }

  /**
   * Run a test
   */
  async test() {
    await this.wake()
    await this.start()
    await this.pause(2000)
    await this.stop()
    await this.sleep()
  }
}
