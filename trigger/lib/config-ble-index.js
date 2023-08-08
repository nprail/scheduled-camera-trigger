import flat from 'flat'

export const index = {
  0: 'name',
  1: 'wakeUpTimeout',
  2: 'startBefore',
  3: 'endAfter',
  4: 'cameraType',
  5: 'logFile',
  6: 'deviceId',
  7: 'deviceName',
  8: 'generic.releaseGpioPort',
  9: 'generic.focusGpioPort',
  10: 'zcam.cameraIp',
  11: 'attempts',
}

export const getConfigByIndex = (config, configIndex, secondIndex) => {
  console.log(flat(config))
  const configName = index[configIndex]
  return flat(config)[configName]
}
