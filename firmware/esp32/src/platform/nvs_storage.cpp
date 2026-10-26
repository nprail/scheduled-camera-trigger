#include "nvs_storage.h"

#include <Preferences.h>

using namespace kairos;

namespace {
constexpr const char* kCfg = "cfg";
constexpr const char* kBlob = "blob";
constexpr const char* kAuth = "auth";
constexpr const char* kSecret = "secret";
}  // namespace

bool NvsStorage::loadConfig(uint32_t& rev, Bytes& blob) {
  Preferences p;
  if (!p.begin(kCfg, false)) return false;
  const size_t n = p.getBytesLength(kBlob);
  if (n < 5) {
    p.end();
    return false;
  }
  Bytes raw(n);
  const size_t got = p.getBytes(kBlob, raw.data(), n);
  p.end();
  if (got != n) return false;
  Reader r(raw);
  rev = r.u32();
  blob.assign(raw.begin() + 4, raw.end());
  return true;
}

bool NvsStorage::saveConfig(uint32_t rev, const Bytes& blob) {
  Writer w;
  w.u32(rev).raw(blob);
  Preferences p;
  if (!p.begin(kCfg, false)) return false;
  const size_t written = p.putBytes(kBlob, w.bytes().data(), w.size());  // one key: a power loss keeps the old or the new
  p.end();
  return written == w.size();
}

bool NvsStorage::loadSecret(uint8_t out[16]) {
  Preferences p;
  if (!p.begin(kAuth, false)) return false;
  const bool ok = p.getBytesLength(kSecret) == 16 && p.getBytes(kSecret, out, 16) == 16;
  p.end();
  return ok;
}

bool NvsStorage::saveSecret(const uint8_t secret[16]) {
  Preferences p;
  if (!p.begin(kAuth, false)) return false;
  const size_t written = p.putBytes(kSecret, secret, 16);
  p.end();
  return written == 16;
}
