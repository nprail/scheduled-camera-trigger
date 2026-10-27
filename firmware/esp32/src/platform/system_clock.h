// The device clock: the ESP32's system clock, restored from the RTC at boot and set by the phone.
#pragma once
#include <sys/time.h>

#include <kairos.h>

#include "rtc_ds3231.h"

class SystemClock : public kairos::Clock {
 public:
  explicit SystemClock(Ds3231& rtc) : rtc_(rtc) {}

  /// Restore the clock from the RTC. If the RTC is missing or untrusted the clock stays invalid
  /// and the schedule is not armed until a phone sets it.
  void begin();

  uint64_t nowMs() override {
    timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<uint64_t>(tv.tv_sec) * 1000 + static_cast<uint64_t>(tv.tv_usec) / 1000;
  }
  void setNowMs(uint64_t ms) override;
  bool valid() override { return valid_; }

  /// Compare with the RTC and step the system clock if they differ by a whole second. Call every
  /// 10 minutes while idle. Returns true if the clock was stepped.
  bool discipline();

 private:
  Ds3231& rtc_;
  bool valid_ = false;
};
