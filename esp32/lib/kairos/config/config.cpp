#include "config.h"

#include <set>

namespace kairos {

Bytes encodeConfig(const Config& c) {
  Writer w;
  w.u8(kConfigLayout).u8(static_cast<uint8_t>(c.cameraType));
  w.u16(c.wakeUpTimeout).u16(c.startBefore).u16(c.endAfter);
  w.u8(c.buttonPin).u8(c.releasePin).u8(c.focusPin);
  w.raw(c.cameraIp, 4);
  w.str(c.name, kMaxName);
  size_t count = c.attempts.size() > kMaxAttempts ? kMaxAttempts : c.attempts.size();
  w.u8(static_cast<uint8_t>(count));
  for (size_t i = 0; i < count; i++) w.u32(c.attempts[i].time).str(c.attempts[i].name, kMaxName);
  return w.take();
}

bool decodeConfig(const uint8_t* data, size_t len, Config& out) {
  Reader r(data, len);
  if (r.u8() != kConfigLayout) return false;
  uint8_t type = r.u8();
  if (type > 1) return false;
  Config c;
  c.cameraType = static_cast<CameraType>(type);
  c.wakeUpTimeout = r.u16();
  c.startBefore = r.u16();
  c.endAfter = r.u16();
  c.buttonPin = r.u8();
  c.releasePin = r.u8();
  c.focusPin = r.u8();
  r.raw(c.cameraIp, 4);
  c.name = r.str();
  uint8_t count = r.u8();
  if (count > kMaxAttempts) return false;
  for (uint8_t i = 0; i < count; i++) {
    Attempt a;
    a.time = r.u32();
    a.name = r.str();
    c.attempts.push_back(std::move(a));
  }
  if (!r.ok()) return false;
  out = std::move(c);
  return true;
}

std::string ipToString(const uint8_t ip[4]) {
  return std::to_string(ip[0]) + "." + std::to_string(ip[1]) + "." + std::to_string(ip[2]) + "." + std::to_string(ip[3]);
}

static std::optional<ValidationError> fail(Field f, Reason r, uint8_t index = 0) {
  ValidationError e;
  e.field = f;
  e.reason = r;
  e.index = index;
  return e;
}

std::optional<ValidationError> validateConfig(const Config& c, uint64_t nowMs, const Pins* pins) {
  if (c.name.size() > kMaxName) return fail(Field::Name, Reason::Range);
  if (c.wakeUpTimeout > 3600) return fail(Field::WakeUpTimeout, Reason::Range);
  if (c.startBefore > 3600) return fail(Field::StartBefore, Reason::Range);
  if (c.endAfter > 3600) return fail(Field::EndAfter, Reason::Range);
  if (pins) {
    if (c.buttonPin != pins->button) return fail(Field::ButtonPin, Reason::BadValue);
    if (c.releasePin != pins->release) return fail(Field::ReleasePin, Reason::BadValue);
    if (c.focusPin != pins->focus) return fail(Field::FocusPin, Reason::BadValue);
  }

  if (c.cameraType == CameraType::Generic) {
    if (c.releasePin == kNoPin) return fail(Field::ReleasePin, Reason::Required);
  } else if (!c.hasIp()) {
    return fail(Field::CameraIp, Reason::Required);
  }

  if (c.attempts.size() > kMaxAttempts) return fail(Field::Attempts, Reason::TooMany);

  std::set<uint32_t> seen;
  for (size_t i = 0; i < c.attempts.size(); i++) {
    const Attempt& a = c.attempts[i];
    uint8_t idx = static_cast<uint8_t>(i);
    if (a.name.empty() || a.name.size() > kMaxName) return fail(Field::AttemptName, Reason::Range, idx);
    if (static_cast<uint64_t>(a.time) * 1000 <= nowMs) return fail(Field::AttemptTime, Reason::Past, idx);
    if (!seen.insert(a.time).second) return fail(Field::AttemptTime, Reason::Duplicate, idx);
  }

  // the camera must be woken in the future for an attempt to run at all
  uint64_t leadMs = (static_cast<uint64_t>(c.startBefore) + c.wakeUpTimeout) * 1000;
  for (size_t i = 0; i < c.attempts.size(); i++) {
    uint64_t t = static_cast<uint64_t>(c.attempts[i].time) * 1000;
    if (t > nowMs && t - leadMs <= nowMs) return fail(Field::AttemptTime, Reason::TooSoon, static_cast<uint8_t>(i));
  }
  return std::nullopt;
}

}  // namespace kairos
