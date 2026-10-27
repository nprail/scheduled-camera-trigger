// Runs decoded requests against the device (docs/ble-protocol.md, section 4). Pure C++.
#pragma once
#include <vector>

#include "auth.h"
#include "device_api.h"
#include "../update/updater.h"
#include "messages.h"

namespace kairos {

class Random {
 public:
  virtual ~Random() = default;
  virtual void fill(uint8_t* out, size_t n) = 0;
};

/// One connection's protocol state.
struct Session {
  bool authed = false;
  bool haveNonce = false;
  uint8_t nonce[kNonceLen] = {0};
  bool logSub = false;
};

struct Reply {
  Bytes message;
  std::vector<Bytes> events;  // sent after the reply (the log replay of logs.subscribe)
};

class Dispatcher {
 public:
  Dispatcher(DeviceApi& api, Random& random) : api_(api), random_(random) {}
  Reply handle(Session& session, const Request& req);
  Lockout& lockout() { return lockout_; }

  /// Enable the fw.* ops. `monotonicMs` is a clock that never steps (the transfer timeouts use it).
  void setFirmware(Updater* updater, std::function<uint64_t()> monotonicMs) {
    fw_ = updater;
    fwClock_ = std::move(monotonicMs);
  }

 private:
  Bytes error(uint8_t id, Err code, ErrorDetail detail = ErrorDetail());
  DeviceApi& api_;
  Random& random_;
  Lockout lockout_;
  Updater* fw_ = nullptr;
  std::function<uint64_t()> fwClock_;
};

}  // namespace kairos
