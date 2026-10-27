// The tag inside every firmware image that holds its version (docs/ble-protocol.md, section 7).
#pragma once
#include <stdint.h>

struct FwTag {
  char magic[8];  // "KAIROSFW"
  uint8_t tagVersion;
  uint8_t chip;  // 1 ESP32-S3, 2 ESP32-C3
  char version[32];
};

extern "C" const FwTag kairos_fw_tag;
