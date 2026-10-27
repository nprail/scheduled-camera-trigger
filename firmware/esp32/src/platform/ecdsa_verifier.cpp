#include "ecdsa_verifier.h"

#include <mbedtls/bignum.h>
#include <mbedtls/ecdsa.h>
#include <mbedtls/ecp.h>

#include "signing_keys.h"

bool EcdsaVerifier::verify(const uint8_t hash[32], const uint8_t sig[64]) {
  mbedtls_ecp_group grp;
  mbedtls_ecp_point q;
  mbedtls_mpi r, s;
  mbedtls_ecp_group_init(&grp);
  mbedtls_ecp_point_init(&q);
  mbedtls_mpi_init(&r);
  mbedtls_mpi_init(&s);

  bool ok = false;
  if (mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP256R1) == 0 && mbedtls_mpi_read_binary(&r, sig, 32) == 0 &&
      mbedtls_mpi_read_binary(&s, sig + 32, 32) == 0) {
    for (unsigned i = 0; i < kairos::kSigningKeyCount && !ok; i++) {
      ok = mbedtls_ecp_point_read_binary(&grp, &q, kairos::kSigningKeys[i], 65) == 0 &&
           mbedtls_ecdsa_verify(&grp, hash, 32, &q, &r, &s) == 0;
    }
  }

  mbedtls_mpi_free(&s);
  mbedtls_mpi_free(&r);
  mbedtls_ecp_point_free(&q);
  mbedtls_ecp_group_free(&grp);
  return ok;
}
