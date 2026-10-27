// Reading firmware images and release manifests (docs/ble-protocol.md, section 7). The phone apps do this
// before anything is sent to a device; this is the reference they are tested against
// (protocol/test-vectors.json). The Pi itself never updates over Bluetooth.

export const IMAGE_MAGIC = 0xe9
export const APP_DESC_MAGIC = 0xabcd5432
const HEADER_CHIP_OFFSET = 12
const APP_DESC_OFFSET = 32
export const MIN_PARSE = 64

/** Chip IDs in an image header. */
const HEADER_CHIP = { 9: 'esp32s3', 5: 'esp32c3' }
/** Chip values in the KAIROSFW tag. */
const TAG_CHIP = { 1: 'esp32s3', 2: 'esp32c3' }
const TAG_MAGIC = Buffer.from('KAIROSFW', 'ascii')
const TAG_LEN = 8 + 1 + 1 + 32

/**
 * Check a firmware file for `chip` ('esp32s3' or 'esp32c3').
 * Returns { status, version, chip }. `status` is one of:
 *   ok         an application image for this chip (`version` is its tag version, or null if it has no tag)
 *   not_image  too short, or not an ESP image at all
 *   wrong_chip built for different hardware
 *   not_app    an image, but not an application image (for example the factory image)
 */
export const parseImage = (buf, chip) => {
  if (buf.length < MIN_PARSE || buf[0] !== IMAGE_MAGIC) return { status: 'not_image', version: null, chip: null }
  const imageChip = HEADER_CHIP[buf.readUInt16LE(HEADER_CHIP_OFFSET)] ?? null
  if (imageChip == null || (chip != null && imageChip !== chip)) return { status: 'wrong_chip', version: null, chip: imageChip }
  if (buf.readUInt32LE(APP_DESC_OFFSET) !== APP_DESC_MAGIC) return { status: 'not_app', version: null, chip: imageChip }

  let at = buf.indexOf(TAG_MAGIC)
  while (at >= 0 && at + TAG_LEN <= buf.length) {
    if (buf[at + 8] === 1) {
      const tagChip = TAG_CHIP[buf[at + 9]] ?? null
      if (tagChip != null && chip != null && tagChip !== chip) return { status: 'wrong_chip', version: null, chip: tagChip }
      const raw = buf.subarray(at + 10, at + TAG_LEN)
      const end = raw.indexOf(0)
      const version = raw.subarray(0, end < 0 ? raw.length : end).toString('ascii')
      return { status: 'ok', version: version || null, chip: imageChip }
    }
    at = buf.indexOf(TAG_MAGIC, at + 1)
  }
  return { status: 'ok', version: null, chip: imageChip }
}

const SEMVER = /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/

const parseVersion = (v) => {
  const m = SEMVER.exec(String(v ?? '').trim())
  if (!m) return null
  return { core: [Number(m[1]), Number(m[2]), Number(m[3])], pre: m[4] ? m[4].split('.') : [] }
}

/** -1, 0 or 1; null if either side is not a semantic version (a development build, "dev"). */
export const compareVersions = (a, b) => {
  const x = parseVersion(a)
  const y = parseVersion(b)
  if (!x || !y) return null
  for (let i = 0; i < 3; i++) if (x.core[i] !== y.core[i]) return x.core[i] < y.core[i] ? -1 : 1
  if (!x.pre.length || !y.pre.length) return x.pre.length === y.pre.length ? 0 : x.pre.length ? -1 : 1
  for (let i = 0; i < Math.min(x.pre.length, y.pre.length); i++) {
    const p = x.pre[i]
    const q = y.pre[i]
    if (p === q) continue
    const pn = /^\d+$/.test(p)
    const qn = /^\d+$/.test(q)
    if (pn && qn) return Number(p) < Number(q) ? -1 : 1
    if (pn !== qn) return pn ? -1 : 1
    return p < q ? -1 : 1
  }
  return x.pre.length === y.pre.length ? 0 : x.pre.length < y.pre.length ? -1 : 1
}

const HEX64 = /^[0-9a-f]{64}$/
const HEX128 = /^[0-9a-f]{128}$/

/**
 * Decide what a release manifest means for a device.
 * `input`: { chip, deviceFw, appProtocol }. Returns { status, image }. `status` is one of:
 *   invalid          the manifest is malformed
 *   unavailable      no image for this chip
 *   up_to_date       the device already runs this version
 *   newer_installed  the device runs a newer version than this release
 *   unknown_current  the device runs a build that has no release version (for example "dev")
 *   app_too_old      the image speaks a newer protocol than this app
 *   needs_usb        the device is too old to update to this release over Bluetooth
 *   available        an update is available
 */
export const evaluateManifest = (manifest, { chip, deviceFw, appProtocol }) => {
  const image = manifest?.images?.[chip]
  if (!manifest || parseVersion(manifest.version) == null || typeof manifest.images !== 'object' || !Number.isInteger(manifest.protocol)) {
    return { status: 'invalid', image: null }
  }
  if (image == null) return { status: 'unavailable', image: null }
  const ok = typeof image.file === 'string' && Number.isInteger(image.size) && image.size > 0 &&
    HEX64.test(image.sha256 ?? '') && HEX128.test(image.sig ?? '')
  if (!ok) return { status: 'invalid', image: null }

  const cmp = compareVersions(manifest.version, deviceFw)
  if (cmp == null) return { status: 'unknown_current', image }
  if (cmp === 0) return { status: 'up_to_date', image }
  if (cmp < 0) return { status: 'newer_installed', image }
  if (manifest.protocol > appProtocol) return { status: 'app_too_old', image }
  if (manifest.minFrom != null && compareVersions(deviceFw, manifest.minFrom) === -1) return { status: 'needs_usb', image }
  return { status: 'available', image }
}
