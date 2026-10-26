// Interfaces the scheduler and controller need from the platform, so they run unchanged in host tests. Pure C++.
#pragma once
#include <cstdint>

#include "../protocol/bytes.h"

namespace kairos {

/// The device clock. On the device it is the system clock, kept right by the external RTC and the phone.
class Clock {
 public:
  virtual ~Clock() = default;
  /// Unix milliseconds.
  virtual uint64_t nowMs() = 0;
  /// Set the clock (and the RTC behind it). The clock is valid afterwards.
  virtual void setNowMs(uint64_t ms) = 0;
  /// True once the clock was set by a phone or restored from a valid RTC.
  virtual bool valid() = 0;
};

/// Persistent storage: the config (its revision and the Config layout bytes, kept together so a save is atomic),
/// and the pairing secret.
class Storage {
 public:
  virtual ~Storage() = default;
  virtual bool loadConfig(uint32_t& rev, Bytes& blob) = 0;
  virtual bool saveConfig(uint32_t rev, const Bytes& blob) = 0;
  virtual bool loadSecret(uint8_t out[16]) = 0;
  virtual bool saveSecret(const uint8_t secret[16]) = 0;
};

}  // namespace kairos
