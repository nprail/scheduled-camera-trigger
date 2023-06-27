import { readFile, appendFile } from 'fs/promises'
import { resolve } from 'path'

export class Logger {
  constructor({ logFile }) {
    this.logFile = resolve(logFile)
    this.logMessages = []
  }

  log(namespace, message, data = {}) {
    const log = {
      timestamp: new Date().toISOString(),
      namespace: namespace.toUpperCase(),
      message,
      data,
    }

    console.log(`${log.timestamp} [${log.namespace}] ${log.message}`, log.data)

    const messageString = `\n${JSON.stringify(log)}`

    this.logMessages.push(log)

    appendFile(this.logFile, messageString).catch(console.error)
  }
}

export const readJson = async (path) => {
  const data = await readFile(path, 'utf-8')
  const json = JSON.parse(data)

  return json
}
