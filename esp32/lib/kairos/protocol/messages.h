// Binary messages of protocol v2 (docs/ble-protocol.md, section 4). The device decodes requests and
// encodes responses, events, Status and Device Info. Pure C++.
#pragma once
#include <string>
#include <vector>

#include "../config/config.h"
#include "auth.h"
#include "bytes.h"
#include "protocol.h"

namespace kairos {

struct Request {
  Op op = Op::Unknown;
  uint8_t code = 0;  // the raw op byte, for unknown ops
  uint8_t id = 0;
  uint8_t clientNonce[kNonceLen] = {0};
  uint8_t proof[kProofLen] = {0};
  uint8_t secret[kSecretLen] = {0};
  uint64_t nowMs = 0;
  Config config;
  uint32_t after = 0;
  uint8_t limit = 0;
  bool enabled = false;
};

/// Decode a request message. Returns false if it is too short or its body is malformed; `id` is the id
/// that could be read (0 if none), for the error reply. An unknown op code decodes as Op::Unknown.
bool decodeRequest(const Bytes& msg, Request& out, uint8_t& id);

struct LogLine {
  uint32_t seq = 0;
  uint32_t ts = 0;  // Unix seconds
  std::string ns;   // at most 8 bytes
  std::string msg;  // at most 120 bytes
};

struct HelloResp {
  uint8_t v = kProtocolVersion;
  bool paired = false;
  uint8_t nonce[kNonceLen] = {0};
  uint64_t nowMs = 0;
  uint16_t maxMessage = kMaxMessage;
};

struct ErrorDetail {
  uint16_t retryAfter = 0;          // locked_out
  Field field = Field::None;        // invalid_config
  uint8_t index = 0;
  Reason reason = Reason::None;
  std::string message;              // internal
};

Bytes encodeEmptyResponse(uint8_t id);
Bytes encodeHelloResponse(uint8_t id, const HelloResp& r);
Bytes encodeAuthResponse(uint8_t id, const uint8_t proof[kProofLen]);
Bytes encodeTimeGetResponse(uint8_t id, uint64_t nowMs);
Bytes encodeTimeSetResponse(uint8_t id, uint64_t nowMs, int32_t offsetMs);
Bytes encodeConfigGetResponse(uint8_t id, const Config& c);
Bytes encodeConfigSetResponse(uint8_t id, uint32_t configRev);
Bytes encodeJobsResponse(uint8_t id, const std::vector<JobState>& states);
Bytes encodeCameraResponse(uint8_t id, bool recording);
Bytes encodeLogsGetResponse(uint8_t id, uint32_t last, bool more, const std::vector<LogLine>& lines);
Bytes encodeError(uint8_t id, Err code, const ErrorDetail& detail = ErrorDetail());

Bytes encodeJobEvent(uint8_t attempt, JobStep step, uint32_t atSec);
Bytes encodeConfigEvent(uint32_t configRev);
Bytes encodeTestEvent(TestStep step, bool ok, const std::string& error);
Bytes encodeLogEvent(const LogLine& line);

struct StatusValue {
  uint8_t flags = 0;
  Phase phase = Phase::Idle;
  uint32_t configRev = 0;
  uint32_t nextEventAt = 0;
  EventType nextEventType = EventType::None;
  uint8_t attemptsRemaining = 0;
  uint8_t nextAttempt = kNoAttempt;
};
constexpr size_t kStatusLen = 13;
Bytes encodeStatus(const StatusValue& s);

struct DeviceInfo {
  uint8_t v = kProtocolVersion;
  uint8_t id[3] = {0, 0, 0};
  CameraType cam = CameraType::Generic;
  std::string fw;
  std::string name;
};
Bytes encodeDeviceInfo(const DeviceInfo& d);

/// A 6-hex-digit id ("A1B2C3") to its 3 bytes. Returns false for anything else.
bool parseDeviceId(const std::string& hex, uint8_t out[3]);
std::string formatDeviceId(const uint8_t id[3]);

/// `{name}-{id}`, or just the id: the name is cut so the id always fits the 29-byte advertised name.
std::string advertisedName(const std::string& name, const std::string& deviceId);

}  // namespace kairos
