import { execFile } from 'child_process'

const run = (cmd, args) =>
  new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: 10_000 }, (err, stdout, stderr) => {
      if (err) {
        err.message = `${cmd} ${args.join(' ')}: ${stderr || err.message}`
        return reject(err)
      }
      resolve(stdout)
    })
  })

/**
 * OS-level operations the BLE protocol needs. `exec` is injectable for tests.
 * Requires root (the Pi service runs as root for HCI access anyway).
 */
export class System {
  constructor({ exec = run } = {}) {
    this.exec = exec
    this.wifiEnabled = true
    this.clockSetSinceBoot = false
  }

  /** Set the system clock to the given epoch milliseconds. */
  async setTime(epochMs) {
    // NTP would immediately fight a manual set (and there's no network in the field)
    await this.exec('timedatectl', ['set-ntp', 'false']).catch(() => {})
    await this.exec('date', ['-u', '-s', `@${(epochMs / 1000).toFixed(3)}`])
    this.clockSetSinceBoot = true
  }

  async setWifi(enabled) {
    await this.exec('rfkill', [enabled ? 'unblock' : 'block', 'wifi'])
    this.wifiEnabled = enabled
  }
}
