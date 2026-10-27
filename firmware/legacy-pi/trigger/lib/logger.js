import { appendFile } from 'fs/promises'
import { resolve } from 'path'
import { EventEmitter } from 'events'

const MAX_MESSAGES = 500

export class Logger extends EventEmitter {
  constructor({ logFile }) {
    super()
    this.logFile = resolve(logFile)
    this.logMessages = []
    this.seq = 0
  }

  log(namespace, message, data = {}) {
    const log = {
      seq: ++this.seq,
      timestamp: new Date().toISOString(),
      namespace: namespace.toUpperCase(),
      message,
      data,
    }

    console.log(`${log.timestamp} [${log.namespace}] ${log.message}`, log.data)

    const messageString = `\n${JSON.stringify(log)}`

    this.logMessages.push(log)
    if (this.logMessages.length > MAX_MESSAGES) {
      this.logMessages.splice(0, this.logMessages.length - MAX_MESSAGES)
    }

    appendFile(this.logFile, messageString).catch(console.error)

    this.emit('log', log)
  }

  /** Log lines with seq > after, oldest first. */
  since(after = 0, limit = 50) {
    return this.logMessages.filter((l) => l.seq > after).slice(0, limit)
  }
}
