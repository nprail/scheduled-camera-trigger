// The device itself: owns the config, the pairing secret and the scheduler, and implements what the
// protocol asks of it (a port of trigger/controller.js and the Status source). Pure C++.
#pragma once
#include <functional>

#include "../protocol/auth.h"
#include "../protocol/device_api.h"
#include "scheduler.h"

namespace kairos {

class Controller : public DeviceApi {
 public:
  Controller(Clock& clock, Storage& storage, RamLog& log, Scheduler& scheduler)
      : clock_(clock), storage_(storage), log_(log), scheduler_(scheduler) {}

  /// Load the config and secret from storage (or use the defaults) and plan the schedule.
  void begin();

  /// Called after a successful config save, with the new revision.
  void onConfigChanged(std::function<void(uint32_t)> fn) { configChanged_ = std::move(fn); }

  /// The physical button.
  void openPairing() {
    pairing_.open(clock_.nowMs());
    log_.log("BLE", "Pairing window open for 120 s");
  }

  StatusValue status();
  uint32_t configRev() const { return rev_; }
  const Config& currentConfig() const { return config_; }
  bool paired() const { return hasSecret_; }

  // DeviceApi
  uint64_t nowMs() override { return clock_.nowMs(); }
  bool secret(uint8_t out[kSecretLen]) override;
  void setSecret(const uint8_t secret[kSecretLen]) override;
  bool pairingOpen() override { return pairing_.isOpen(clock_.nowMs()); }
  void closePairing() override { pairing_.close(); }
  bool setTime(uint64_t nowMs, int32_t& offsetMs) override;
  const Config& config() override { return config_; }
  SaveResult saveConfig(const Config& c) override;
  std::vector<JobState> jobStates() override;
  CameraResult camera(Op op, bool& recording) override;
  bool startTest() override { return scheduler_.runTest(); }
  std::vector<LogLine> logsSince(uint32_t after, size_t limit) override { return log_.since(after, limit); }
  uint32_t lastLogSeq() override { return log_.seq(); }

 private:
  Clock& clock_;
  Storage& storage_;
  RamLog& log_;
  Scheduler& scheduler_;
  Config config_;
  uint32_t rev_ = 1;
  uint8_t secret_[kSecretLen] = {0};
  bool hasSecret_ = false;
  PairingWindow pairing_;
  std::function<void(uint32_t)> configChanged_;
};

}  // namespace kairos
