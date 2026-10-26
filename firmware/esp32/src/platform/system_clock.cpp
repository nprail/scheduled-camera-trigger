#include "system_clock.h"

void SystemClock::begin() {
  uint32_t sec = 0;
  if (rtc_.read(sec)) {
    timeval tv = {static_cast<time_t>(sec), 0};
    settimeofday(&tv, nullptr);
    valid_ = true;
  }
}

void SystemClock::setNowMs(uint64_t ms) {
  timeval tv = {static_cast<time_t>(ms / 1000), static_cast<suseconds_t>((ms % 1000) * 1000)};
  settimeofday(&tv, nullptr);
  rtc_.write(static_cast<uint32_t>(ms / 1000));
  valid_ = true;
}

bool SystemClock::discipline() {
  uint32_t rtcSec = 0;
  if (!valid_ || !rtc_.read(rtcSec)) return false;
  const uint32_t sysSec = static_cast<uint32_t>(nowMs() / 1000);
  // the RTC has 1 s resolution and is written truncated, so it can read up to a second behind the system
  // clock without any drift: only a bigger difference counts. Step to the middle of the RTC's second.
  const int64_t diff = static_cast<int64_t>(sysSec) - static_cast<int64_t>(rtcSec);
  if (diff > -2 && diff < 2) return false;
  timeval tv = {static_cast<time_t>(rtcSec), 500000};
  settimeofday(&tv, nullptr);
  return true;
}
