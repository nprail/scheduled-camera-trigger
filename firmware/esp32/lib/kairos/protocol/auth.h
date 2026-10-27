// Authentication and pairing (docs/ble-protocol.md, Security). Pure C++.
#pragma once
#include <cstdint>

#include "bytes.h"

namespace kairos {

constexpr size_t kSecretLen = 16;
constexpr size_t kNonceLen = 16;
constexpr size_t kProofLen = 32;

/// HMAC-SHA256(secret, "kairos-auth-v2" || nonce || clientNonce)
void clientProof(const uint8_t secret[kSecretLen], const uint8_t nonce[kNonceLen], const uint8_t clientNonce[kNonceLen], uint8_t out[kProofLen]);
/// HMAC-SHA256(secret, "kairos-auth-v2-srv" || nonce || clientNonce)
void serverProof(const uint8_t secret[kSecretLen], const uint8_t nonce[kNonceLen], const uint8_t clientNonce[kNonceLen], uint8_t out[kProofLen]);

/// Constant-time comparison.
bool safeEqual(const uint8_t* a, const uint8_t* b, size_t n);

/// The pairing window opened by the physical button; closes on its own.
class PairingWindow {
 public:
  explicit PairingWindow(uint32_t durationMs = 120000) : durationMs_(durationMs) {}
  void open(uint64_t nowMs) { openUntil_ = nowMs + durationMs_; }
  void close() { openUntil_ = 0; }
  bool isOpen(uint64_t nowMs) const { return nowMs < openUntil_; }

 private:
  uint32_t durationMs_;
  uint64_t openUntil_ = 0;
};

/// Failed-attempt tracking shared by all connections: 5 failures lock auth for 30 s, doubling up to 5 min.
class Lockout {
 public:
  Lockout(uint32_t threshold = 5, uint32_t baseMs = 30000, uint32_t maxMs = 300000) : threshold_(threshold), baseMs_(baseMs), maxMs_(maxMs) {}
  /// Seconds left in the lockout, or 0.
  uint32_t retryAfter(uint64_t nowMs) const { return nowMs >= until_ ? 0 : static_cast<uint32_t>((until_ - nowMs + 999) / 1000); }
  void fail(uint64_t nowMs);
  void success() { failures_ = 0; level_ = 0; until_ = 0; }

 private:
  uint32_t threshold_, baseMs_, maxMs_;
  uint32_t failures_ = 0;
  uint32_t level_ = 0;
  uint64_t until_ = 0;
};

}  // namespace kairos
