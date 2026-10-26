// The external RTC: a DS3231 on I2C (address 0x68). It stores UTC with 1 s resolution.
#pragma once
#include <cstdint>

class Ds3231 {
 public:
  /// Start the I2C bus. Returns true if the chip answers.
  bool begin(int sda, int scl);
  /// Read the time. Returns false if the chip doesn't answer or its time can't be trusted
  /// (the oscillator-stop flag is set, or the year is before 2025).
  bool read(uint32_t& unixSec);
  /// Set the time and clear the oscillator-stop flag.
  bool write(uint32_t unixSec);
  bool present() const { return present_; }

 private:
  bool present_ = false;
};

// Civil date helpers (proleptic Gregorian, UTC), exposed for the tests.
int64_t daysFromCivil(int y, unsigned m, unsigned d);
void civilFromDays(int64_t days, int& y, unsigned& m, unsigned& d);
