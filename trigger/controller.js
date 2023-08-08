export const initController = ({ configFile, scheduler, logger }) => {
  const test = async () => {
    await scheduler.cam.test()
  }

  const save = async (newConfig) => {
    if (!newConfig.logFile || !newConfig.cameraType) {
      const error = new Error('Config incomplete')
      error.status = 400

      throw error
    }

    await saveJson(configFile, newConfig)

    scheduler.teardown()
    scheduler.initialize(newConfig)
  }

  return { test, save }
}
