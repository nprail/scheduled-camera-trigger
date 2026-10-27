// A small tag inside every firmware image so the apps can show its version before it is installed
// (docs/ble-protocol.md, section 7). `used` plus a reference from main.cpp keeps the linker from dropping it.
#include <sdkconfig.h>

#include "fw_tag.h"

#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif

#if CONFIG_IDF_TARGET_ESP32C3
#define KAIROS_TAG_CHIP 2
#else
#define KAIROS_TAG_CHIP 1
#endif

namespace {
constexpr FwTag makeTag(const char* v) {
  FwTag t{{'K', 'A', 'I', 'R', 'O', 'S', 'F', 'W'}, 1, KAIROS_TAG_CHIP, {}};
  for (int i = 0; i < 31 && v[i]; i++) t.version[i] = v[i];
  return t;
}
}  // namespace

extern "C" {
const FwTag kairos_fw_tag __attribute__((used)) = makeTag(FW_VERSION);
}
