/**
 * Physical button on a GPIO pin (rising edge). Resolves to a disposer, or
 * a no-op if GPIO isn't available (e.g. development off the Pi).
 */
export const initButton = async ({ port, onPress, logger }) => {
  if (port == null) return () => {}

  try {
    const { Gpio } = await import('onoff')
    const button = new Gpio(port, 'in', 'rising', { debounceTimeout: 50 })
    button.watch((err) => {
      if (err) return logger.log('button', err?.message ?? err)
      logger.log('button', 'Pressed')
      onPress()
    })
    return () => button.unexport()
  } catch (err) {
    logger.log('button', `Unavailable: ${err?.message ?? err}`)
    return () => {}
  }
}
