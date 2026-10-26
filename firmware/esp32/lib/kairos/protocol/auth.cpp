#include "auth.h"

#include <cstring>

#include "sha256.h"

namespace kairos {

static void proof(const char* label, const uint8_t* secret, const uint8_t* nonce, const uint8_t* clientNonce, uint8_t* out) {
  const uint8_t* parts[3] = {reinterpret_cast<const uint8_t*>(label), nonce, clientNonce};
  const size_t lens[3] = {std::strlen(label), kNonceLen, kNonceLen};
  hmacSha256(secret, kSecretLen, parts, lens, out);
}

void clientProof(const uint8_t secret[kSecretLen], const uint8_t nonce[kNonceLen], const uint8_t clientNonce[kNonceLen], uint8_t out[kProofLen]) {
  proof("kairos-auth-v2", secret, nonce, clientNonce, out);
}

void serverProof(const uint8_t secret[kSecretLen], const uint8_t nonce[kNonceLen], const uint8_t clientNonce[kNonceLen], uint8_t out[kProofLen]) {
  proof("kairos-auth-v2-srv", secret, nonce, clientNonce, out);
}

bool safeEqual(const uint8_t* a, const uint8_t* b, size_t n) {
  uint8_t diff = 0;
  for (size_t i = 0; i < n; i++) diff |= a[i] ^ b[i];
  return diff == 0;
}

void Lockout::fail(uint64_t nowMs) {
  if (++failures_ >= threshold_) {
    uint64_t ms = static_cast<uint64_t>(baseMs_) << (level_ > 16 ? 16 : level_);
    if (ms > maxMs_) ms = maxMs_;
    level_++;
    failures_ = 0;
    until_ = nowMs + ms;
  }
}

}  // namespace kairos
