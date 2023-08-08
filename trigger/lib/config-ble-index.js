export const SERVICE_UUID = '530c35d9-866f-4fb1-87f6-11c0f598ea84'

export const CONFIG_WRITE_CHARACTERISTIC_UUID =
  '90b4137b-88d7-4b15-a48b-13f9077a487e'
export const CONFIG_READ_CHARACTERISTIC_UUID =
  '84425c54-3368-43f6-933c-ef2fba2b1a52'
export const CONFIG_NOTIFICATION_CHARACTERISTIC_UUID =
  '18f13e24-eb64-4ccd-b67c-4eda2b0af806'
export const RUN_TEST_CHARACTERISTIC_UUID =
  'e7a9479e-cec7-423c-8338-69f55fc04b5f'

export const CONFIG_NAME_INDEX = {
  0: 'name',
  1: 'wakeUpTimeout',
  2: 'startBefore',
  3: 'endAfter',
  4: 'cameraType',
  5: 'logFile',
  6: 'deviceId',
  7: 'deviceName',
  8: 'genericReleaseGpioPin',
  9: 'genericFocusGpioPin',
  10: 'zcamCameraIp',
  11: 'attempts',
}

export const cameraType = {
  0: 'generic',
  1: 'zcam',
}

export const getConfigValueByIndex = (config, configIndex, arrayIndex) => {
  const configName = CONFIG_NAME_INDEX[configIndex]

  if (arrayIndex) return config[configName][arrayIndex]

  return config[configName]
}
