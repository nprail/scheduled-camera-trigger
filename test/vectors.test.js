import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'fs/promises'
import { build, FILE } from './vectors.js'

test('docs/test-vectors.json matches the reference implementation', async () => {
  const onDisk = JSON.parse(await readFile(FILE, 'utf8'))
  assert.deepEqual(onDisk, build(), 'run: node test/vectors.js --write')
})
