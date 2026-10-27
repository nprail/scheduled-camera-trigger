import test from 'node:test'
import assert from 'node:assert/strict'

import { System } from '../trigger/lib/system.js'

test('disableWifi blocks the Wi-Fi radio with rfkill', async () => {
  const calls = []
  const system = new System({ exec: async (cmd, args) => calls.push([cmd, ...args]) })
  await system.disableWifi()
  assert.deepEqual(calls, [['rfkill', 'block', 'wifi']])
})

test('disableWifi reports a failing rfkill', async () => {
  const system = new System({
    exec: async () => {
      throw new Error('rfkill: not found')
    },
  })
  await assert.rejects(system.disableWifi(), /rfkill/)
})
