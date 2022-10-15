import { readFile, appendFile } from 'fs/promises'
import { resolve } from 'path'

export class Logger {
  constructor({ logFile, serialPort }) {
    this.serialPort = serialPort
    this.logFile = logFile
  }

  log(message, ...optionalParams) {
    const log = {
      timestamp: new Date().toISOString(),
      message,
      optionalParams,
    }
    console.log(`${log.timestamp} ${log.message}`, ...log.optionalParams)

    const message = `\n${JSON.stringify(log)}`

    this.serialPort.write(message, (err) => {
      if (err) {
        return console.log('Error on write: ', err.message)
      }
    })

    appendFile(resolve(this.logFile), message).catch(console.error)
  }
}

export const readJson = async (path) => {
  const data = await readFile(path, 'utf-8')
  const json = JSON.parse(data)

  return json
}
