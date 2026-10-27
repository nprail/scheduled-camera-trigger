#include "rtc_ds3231.h"

#include <Wire.h>

namespace {
constexpr uint8_t kAddr = 0x68;
constexpr uint8_t kRegTime = 0x00;
constexpr uint8_t kRegStatus = 0x0F;
constexpr uint8_t bcd(uint8_t v) { return static_cast<uint8_t>(((v / 10) << 4) | (v % 10)); }
constexpr uint8_t unbcd(uint8_t v) { return static_cast<uint8_t>((v >> 4) * 10 + (v & 0x0F)); }
}  // namespace

int64_t daysFromCivil(int y, unsigned m, unsigned d) {
  y -= m <= 2;
  const int64_t era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(y - era * 400);
  const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return era * 146097 + static_cast<int64_t>(doe) - 719468;
}

void civilFromDays(int64_t z, int& y, unsigned& m, unsigned& d) {
  z += 719468;
  const int64_t era = (z >= 0 ? z : z - 146096) / 146097;
  const unsigned doe = static_cast<unsigned>(z - era * 146097);
  const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  y = static_cast<int>(yoe) + static_cast<int>(era) * 400;
  const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  const unsigned mp = (5 * doy + 2) / 153;
  d = doy - (153 * mp + 2) / 5 + 1;
  m = mp < 10 ? mp + 3 : mp - 9;
  y += m <= 2;
}

bool Ds3231::begin(int sda, int scl) {
  Wire.begin(sda, scl);
  Wire.beginTransmission(kAddr);
  present_ = Wire.endTransmission() == 0;
  return present_;
}

bool Ds3231::read(uint32_t& unixSec) {
  if (!present_) return false;
  Wire.beginTransmission(kAddr);
  Wire.write(kRegStatus);
  if (Wire.endTransmission(false) != 0 || Wire.requestFrom(static_cast<int>(kAddr), 1) != 1) return false;
  const uint8_t status = Wire.read();
  if (status & 0x80) return false;  // the oscillator stopped: the time is not trustworthy

  Wire.beginTransmission(kAddr);
  Wire.write(kRegTime);
  if (Wire.endTransmission(false) != 0 || Wire.requestFrom(static_cast<int>(kAddr), 7) != 7) return false;
  const uint8_t sec = unbcd(Wire.read() & 0x7F);
  const uint8_t min = unbcd(Wire.read() & 0x7F);
  const uint8_t hour = unbcd(Wire.read() & 0x3F);  // 24 hour mode
  Wire.read();                                       // day of week
  const uint8_t day = unbcd(Wire.read() & 0x3F);
  const uint8_t month = unbcd(Wire.read() & 0x1F);
  const uint8_t year = unbcd(Wire.read());
  if (year < 25 || month < 1 || month > 12 || day < 1 || day > 31) return false;

  const int64_t days = daysFromCivil(2000 + year, month, day);
  unixSec = static_cast<uint32_t>(days * 86400 + hour * 3600 + min * 60 + sec);
  return true;
}

bool Ds3231::write(uint32_t unixSec) {
  if (!present_) return false;
  const int64_t days = unixSec / 86400;
  const uint32_t rem = unixSec % 86400;
  int y;
  unsigned m, d;
  civilFromDays(days, y, m, d);
  const uint8_t dow = static_cast<uint8_t>((days + 4) % 7 + 1);  // 1970-01-01 was a Thursday; the chip counts 1..7

  Wire.beginTransmission(kAddr);
  Wire.write(kRegTime);
  Wire.write(bcd(rem % 60));
  Wire.write(bcd((rem / 60) % 60));
  Wire.write(bcd(rem / 3600));
  Wire.write(dow);
  Wire.write(bcd(d));
  Wire.write(bcd(m));
  Wire.write(bcd(static_cast<uint8_t>(y - 2000)));
  if (Wire.endTransmission() != 0) return false;

  Wire.beginTransmission(kAddr);  // clear the oscillator-stop flag
  Wire.write(kRegStatus);
  Wire.write(static_cast<uint8_t>(0));
  return Wire.endTransmission() == 0;
}
