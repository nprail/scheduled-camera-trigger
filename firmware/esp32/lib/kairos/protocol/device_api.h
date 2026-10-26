// What the protocol needs from the rest of the firmware. The real implementation is the Controller
// (lib/scheduler); host tests use a fake. Pure C++.
#pragma once
#include <vector>

#include "../config/config.h"
#include "messages.h"

namespace kairos {

enum class SaveStatus : uint8_t { Ok, Busy, Invalid, Failed };

struct SaveResult {
  SaveStatus status = SaveStatus::Ok;
  ValidationError error;  // when Invalid
  // Failed: the config could not be stored (the old config is kept)
  uint32_t configRev = 0;  // when Ok
};

enum class CameraResult : uint8_t { Ok, Busy, Unavailable };

class DeviceApi {
 public:
  virtual ~DeviceApi() = default;

  virtual uint64_t nowMs() = 0;

  /// The pairing secret, or false if the device has none.
  virtual bool secret(uint8_t out[kSecretLen]) = 0;
  virtual void setSecret(const uint8_t secret[kSecretLen]) = 0;
  virtual bool pairingOpen() = 0;
  virtual void closePairing() = 0;

  /// Set the clock. Returns false if the device is busy. `offsetMs` is the device clock minus the new time.
  virtual bool setTime(uint64_t nowMs, int32_t& offsetMs) = 0;

  virtual const Config& config() = 0;
  virtual SaveResult saveConfig(const Config& c) = 0;
  virtual std::vector<JobState> jobStates() = 0;

  virtual CameraResult camera(Op op, bool& recording) = 0;
  /// Start the wake, start, stop, sleep test. Returns false if the camera is busy.
  virtual bool startTest() = 0;

  virtual std::vector<LogLine> logsSince(uint32_t after, size_t limit) = 0;
  virtual uint32_t lastLogSeq() = 0;
};

}  // namespace kairos
