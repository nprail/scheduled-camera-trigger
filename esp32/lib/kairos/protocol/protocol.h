// Constants of protocol v2 (docs/ble-protocol.md). Pure C++.
#pragma once
#include <cstdint>

namespace kairos {

constexpr uint8_t kProtocolVersion = 2;
constexpr uint16_t kMaxMessage = 2048;
constexpr uint8_t kNoPin = 0xFF;
constexpr uint8_t kNoAttempt = 0xFF;
constexpr size_t kMaxAttempts = 30;
constexpr size_t kMaxName = 32;

constexpr const char* kUuidService = "6b610001-6169-4f72-8f73-b3e1c5a97d24";
constexpr const char* kUuidDeviceInfo = "6b610002-6169-4f72-8f73-b3e1c5a97d24";
constexpr const char* kUuidStatus = "6b610003-6169-4f72-8f73-b3e1c5a97d24";
constexpr const char* kUuidCommand = "6b610004-6169-4f72-8f73-b3e1c5a97d24";
constexpr const char* kUuidResponse = "6b610005-6169-4f72-8f73-b3e1c5a97d24";

enum class Op : uint8_t {
  Unknown = 0,
  Hello = 0x01,
  Auth = 0x02,
  AuthPair = 0x03,
  TimeGet = 0x04,
  TimeSet = 0x05,
  ConfigGet = 0x07,
  ConfigSet = 0x08,
  JobsGet = 0x09,
  CameraWake = 0x10,
  CameraStart = 0x11,
  CameraStop = 0x12,
  CameraSleep = 0x13,
  TestRun = 0x14,
  LogsGet = 0x20,
  LogsSubscribe = 0x21,
};

enum class Event : uint8_t { Job = 0x01, Config = 0x02, Test = 0x03, Log = 0x04 };

enum class Err : uint8_t {
  BadRequest = 1,
  Unauthorized = 2,
  LockedOut = 3,
  NotSupported = 4,
  Busy = 5,
  InvalidConfig = 6,
  TooLarge = 7,
  PairingClosed = 8,
  Internal = 9,
};

/// invalid_config fields (0 = unspecified).
enum class Field : uint8_t {
  None = 0, Name = 1, CameraType = 2, WakeUpTimeout = 3, StartBefore = 4, EndAfter = 5,
  CameraIp = 9, Attempts = 10, AttemptName = 11, AttemptTime = 12,
};

enum class Reason : uint8_t { None = 0, Required = 1, Range = 2, BadValue = 3, Past = 4, Duplicate = 5, TooSoon = 6, TooMany = 7 };

enum class Phase : uint8_t { Idle = 0, Awake = 1, Recording = 2, Test = 3, Error = 4 };
enum class EventType : uint8_t { None = 0, Wake = 1, Start = 2, Stop = 3 };
enum class JobState : uint8_t { Pending = 0, Active = 1, Complete = 2, Missed = 3 };
enum class JobStep : uint8_t { Wake = 0, Start = 1, Stop = 2, Complete = 3, Missed = 4 };
enum class TestStep : uint8_t { Wake = 0, Start = 1, Stop = 2, Sleep = 3, Done = 4 };
enum class CameraType : uint8_t { Generic = 0, ZCam = 1 };

namespace flag {
constexpr uint8_t Recording = 1 << 0;
constexpr uint8_t CameraReady = 1 << 1;
constexpr uint8_t PairingOpen = 1 << 2;
constexpr uint8_t ClockValid = 1 << 3;
}  // namespace flag

}  // namespace kairos
