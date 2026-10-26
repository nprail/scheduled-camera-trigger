// SHA-256 and HMAC-SHA256, small and dependency free so the same code runs on the device and in the host tests.
#pragma once
#include <cstddef>
#include <cstdint>

namespace kairos {

class Sha256 {
 public:
  Sha256() { reset(); }
  void reset();
  void update(const uint8_t* data, size_t len);
  void final(uint8_t out[32]);

 private:
  void block(const uint8_t* p);
  uint32_t h_[8];
  uint8_t buf_[64];
  size_t buflen_ = 0;
  uint64_t total_ = 0;
};

/// HMAC-SHA256 over the concatenation of up to three parts (label, nonce, clientNonce).
void hmacSha256(const uint8_t* key, size_t keyLen, const uint8_t* const parts[3], const size_t lens[3], uint8_t out[32]);

}  // namespace kairos
