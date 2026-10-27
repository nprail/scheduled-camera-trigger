// Checks an update's signature with mbedTLS: ECDSA P-256 over a SHA-256 value, against src/platform/signing_keys.h.
#pragma once
#include <kairos.h>

class EcdsaVerifier : public kairos::SignatureVerifier {
 public:
  bool verify(const uint8_t hash[32], const uint8_t sig[64]) override;
};
