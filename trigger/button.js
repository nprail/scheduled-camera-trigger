import { Gpio } from 'onoff'
import shell from 'shelljs'

export const initButton = ({ config, logger }) => {
  if (!config.buttonGpioPort) return

  const button = new Gpio(config.buttonGpioPort, 'in', 'rising', {
    debounceTimeout: 10,
  })

  button.watch((err) => {
    if (err) {
      logger.log('button', err?.message ?? err, err)
    }

    logger.log('button', 'WiFi re-enable button pressed')

    shell.exec('sudo rfkill unblock wifi', (code, stdout, stderr) => {
      logger.log('button', `sudo rfkill unblock wifi: ${stdout || stderr}`, {
        code,
        stderr,
        stdout,
      })
    })
  })

  process.on('SIGINT', (_) => {
    button.unexport()
  })
}
