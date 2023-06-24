import { readFile, appendFile } from 'fs/promises'
import { resolve } from 'path'

export class Logger {
  constructor({ logFile }) {
    this.logFile = logFile
  }

  log(message, ...optionalParams) {
    const log = {
      timestamp: new Date().toISOString(),
      message,
      optionalParams,
    }
    console.log(`${log.timestamp} ${log.message}`, ...log.optionalParams)

    const messageString = `\n${log.timestamp} ${log.message} ${JSON.stringify(log.optionalParams)}`

    appendFile(resolve(this.logFile), messageString).catch(console.error)
  }
}

export const readJson = async (path) => {
  const data = await readFile(path, 'utf-8')
  const json = JSON.parse(data)

  return json
}
