import test from 'node:test'
import assert from 'node:assert/strict'
import { compareVersions, evaluateManifest, parseImage } from '../trigger/ble/firmware-image.js'
import { build } from './vectors.js'

test('versions order like semantic versions', () => {
  assert.equal(compareVersions('1.2.0', '1.10.0'), -1)
  assert.equal(compareVersions('1.0.0', '1.0.0-rc.1'), 1)
  assert.equal(compareVersions('1.0.0-beta.2', '1.0.0-beta.10'), -1)
  assert.equal(compareVersions('v2.0.0', '2.0.0'), 0)
  assert.equal(compareVersions('dev', '1.0.0'), null)
})

test('the vector images and manifests give the documented results', () => {
  const { firmware } = build()
  const status = Object.fromEntries(firmware.images.map((i) => [i.name, i.expected.status]))
  assert.equal(status['app image for the S3, with a tag'], 'ok')
  assert.equal(status['a factory image (no application descriptor)'], 'not_app')
  assert.equal(status['an S3 image offered to a C3'], 'wrong_chip')
  assert.equal(status['too short'], 'not_image')
  assert.equal(parseImage(Buffer.from(firmware.images[0].hex, 'hex'), 'esp32s3').version, '1.3.0')

  const m = Object.fromEntries(firmware.manifests.map((x) => [x.name, x.expected.status]))
  assert.equal(m['an update is available'], 'available')
  assert.equal(m['the same version'], 'up_to_date')
  assert.equal(m['the device is ahead'], 'newer_installed')
  assert.equal(m['a development build'], 'unknown_current')
  assert.equal(m['the device is too old for a Bluetooth update'], 'needs_usb')
  assert.equal(m['the image needs a newer app'], 'app_too_old')
  assert.equal(m['no image for this chip'], 'unavailable')
  assert.equal(m['a missing signature'], 'invalid')
  assert.equal(evaluateManifest(null, { chip: 'esp32s3', deviceFw: '1.0.0', appProtocol: 2 }).status, 'invalid')
})
