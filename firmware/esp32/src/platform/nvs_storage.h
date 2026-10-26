// Persistent storage in NVS: one atomic blob for the config (revision + layout bytes) and one for the
// pairing secret. NVS lives in its own frozen partition, so re-flashing the app keeps both.
#pragma once
#include <kairos.h>

class NvsStorage : public kairos::Storage {
 public:
  bool loadConfig(uint32_t& rev, kairos::Bytes& blob) override;
  bool saveConfig(uint32_t rev, const kairos::Bytes& blob) override;
  bool loadSecret(uint8_t out[16]) override;
  bool saveSecret(const uint8_t secret[16]) override;
};
