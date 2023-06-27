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
}
