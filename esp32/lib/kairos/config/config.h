// The device configuration: the struct, its binary layout (docs/ble-protocol.md, Config layout), and validation.
// The binary layout is also the form stored in flash. Pure C++.
#pragma once
#include <cstring>
#include <optional>
#include <string>
#include <vector>

#include "../protocol/bytes.h"
#include "../protocol/protocol.h"

namespace kairos {

constexpr uint8_t kConfigLayout = 1;

struct Attempt {
  uint32_t time = 0;  // T-0 in Unix seconds
  std::string name;
  bool operator==(const Attempt& o) const { return time == o.time && name == o.name; }
};

struct Config {
  CameraType cameraType = CameraType::Generic;
  uint16_t wakeUpTimeout = 5;  // seconds before recording starts
  uint16_t startBefore = 20;   // seconds before T-0
  uint16_t endAfter = 30;      // seconds after T-0
  uint8_t buttonPin = kNoPin;
  uint8_t releasePin = kNoPin;
  uint8_t focusPin = kNoPin;
  uint8_t cameraIp[4] = {0, 0, 0, 0};
  std::string name;
  std::vector<Attempt> attempts;

  bool operator==(const Config& o) const {
    return cameraType == o.cameraType && wakeUpTimeout == o.wakeUpTimeout && startBefore == o.startBefore &&
           endAfter == o.endAfter && buttonPin == o.buttonPin && releasePin == o.releasePin &&
           focusPin == o.focusPin && std::memcmp(cameraIp, o.cameraIp, 4) == 0 && name == o.name &&
           attempts == o.attempts;
  }
  bool hasIp() const { return cameraIp[0] | cameraIp[1] | cameraIp[2] | cameraIp[3]; }
};

Bytes encodeConfig(const Config& c);

/// Decode a config. Returns false if it is too short, has an unknown layout or camera type, or more than 30 attempts.
bool decodeConfig(const uint8_t* data, size_t len, Config& out);

std::string ipToString(const uint8_t ip[4]);

struct ValidationError {
  Field field = Field::None;
  uint8_t index = 0;  // attempt index, for the attempt fields
  Reason reason = Reason::None;
};

/// The GPIOs the board is wired for. They are constants in the firmware (src/platform/board.h), not part of what a
/// phone can change: a config that names different pins is refused. `kNoPin` means the line is not wired.
struct Pins {
  uint8_t button = kNoPin;
  uint8_t release = kNoPin;
  uint8_t focus = kNoPin;
};

/// Returns the first problem, or nothing. `nowMs` is the device clock (Unix ms). Attempt times must be
/// unique and in the future, and so must the camera wake-up time (`time - startBefore - wakeUpTimeout`).
/// With `pins`, the pins in the config must be exactly the board's.
std::optional<ValidationError> validateConfig(const Config& c, uint64_t nowMs, const Pins* pins = nullptr);

}  // namespace kairos
