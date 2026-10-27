#include "messages.h"

#include <cctype>

namespace kairos {

namespace {
constexpr uint8_t kResponse = 0x80;
constexpr uint8_t kEvent = 0x81;

Writer response(uint8_t id) {
  Writer w;
  w.u8(kResponse).u8(id).u8(0);
  return w;
}

void writeLine(Writer& w, const LogLine& l) {
  w.u32(l.seq).u32(l.ts).str(truncateUtf8(l.ns, 8), 8).str(truncateUtf8(l.msg, 120), 120);
}
}  // namespace

bool decodeRequest(const Bytes& msg, Request& out, uint8_t& id) {
  id = msg.size() >= 2 ? msg[1] : 0;
  if (msg.size() < 2) return false;
  Reader r(msg.data() + 2, msg.size() - 2);
  Request q;
  q.code = msg[0];
  q.id = msg[1];
  q.op = Op::Unknown;
  switch (msg[0]) {
    case 0x01: q.op = Op::Hello; break;
    case 0x02:
      q.op = Op::Auth;
      r.raw(q.clientNonce, kNonceLen);
      r.raw(q.proof, kProofLen);
      break;
    case 0x03:
      q.op = Op::AuthPair;
      r.raw(q.secret, kSecretLen);
      break;
    case 0x04: q.op = Op::TimeGet; break;
    case 0x05:
      q.op = Op::TimeSet;
      q.nowMs = r.u64();
      break;
    case 0x07: q.op = Op::ConfigGet; break;
    case 0x08:
      q.op = Op::ConfigSet;
      if (!decodeConfig(r.ptr(), r.remaining(), q.config)) return false;
      break;
    case 0x09: q.op = Op::JobsGet; break;
    case 0x10: q.op = Op::CameraWake; break;
    case 0x11: q.op = Op::CameraStart; break;
    case 0x12: q.op = Op::CameraStop; break;
    case 0x13: q.op = Op::CameraSleep; break;
    case 0x14: q.op = Op::TestRun; break;
    case 0x20:
      q.op = Op::LogsGet;
      q.after = r.u32();
      q.limit = r.u8();
      break;
    case 0x21:
      q.op = Op::LogsSubscribe;
      q.enabled = r.u8() != 0;
      q.after = r.u32();
      break;
    case 0x30:
      q.op = Op::FwBegin;
      q.fwSize = r.u32();
      r.raw(q.fwSha, 32);
      r.raw(q.fwSig, 64);
      break;
    case 0x31: q.op = Op::FwEnd; break;
    case 0x32: q.op = Op::FwAbort; break;
    default: break;
  }
  if (!r.ok()) return false;
  out = std::move(q);
  return true;
}

Bytes encodeEmptyResponse(uint8_t id) { return response(id).take(); }

Bytes encodeHelloResponse(uint8_t id, const HelloResp& h) {
  Writer w = response(id);
  w.u8(h.v).u8(h.paired ? 1 : 0).raw(h.nonce, kNonceLen).u64(h.nowMs).u16(h.maxMessage);
  return w.take();
}

Bytes encodeAuthResponse(uint8_t id, const uint8_t proof[kProofLen]) {
  Writer w = response(id);
  w.raw(proof, kProofLen);
  return w.take();
}

Bytes encodeTimeGetResponse(uint8_t id, uint64_t nowMs) {
  Writer w = response(id);
  w.u64(nowMs);
  return w.take();
}

Bytes encodeTimeSetResponse(uint8_t id, uint64_t nowMs, int32_t offsetMs) {
  Writer w = response(id);
  w.u64(nowMs).i32(offsetMs);
  return w.take();
}

Bytes encodeConfigGetResponse(uint8_t id, const Config& c) {
  Writer w = response(id);
  w.raw(encodeConfig(c));
  return w.take();
}

Bytes encodeConfigSetResponse(uint8_t id, uint32_t configRev) {
  Writer w = response(id);
  w.u32(configRev);
  return w.take();
}

Bytes encodeJobsResponse(uint8_t id, const std::vector<JobState>& states) {
  Writer w = response(id);
  w.u8(static_cast<uint8_t>(states.size()));
  for (JobState s : states) w.u8(static_cast<uint8_t>(s));
  return w.take();
}

Bytes encodeCameraResponse(uint8_t id, bool recording) {
  Writer w = response(id);
  w.u8(recording ? 1 : 0);
  return w.take();
}

Bytes encodeLogsGetResponse(uint8_t id, uint32_t last, bool more, const std::vector<LogLine>& lines) {
  Writer w = response(id);
  w.u32(last).u8(more ? 1 : 0).u8(static_cast<uint8_t>(lines.size()));
  for (const auto& l : lines) writeLine(w, l);
  return w.take();
}

Bytes encodeFwBeginResponse(uint8_t id, uint32_t offset, uint16_t window) {
  Writer w = response(id);
  w.u32(offset).u16(window);
  return w.take();
}

Bytes encodeFwEndResponse(uint8_t id, uint16_t rebootInMs) {
  Writer w = response(id);
  w.u16(rebootInMs);
  return w.take();
}

Bytes encodeError(uint8_t id, Err code, const ErrorDetail& d) {
  Writer w;
  w.u8(kResponse).u8(id).u8(static_cast<uint8_t>(code));
  if (code == Err::LockedOut) {
    w.u16(d.retryAfter);
  } else if (code == Err::InvalidConfig) {
    w.u8(static_cast<uint8_t>(d.field)).u8(d.index).u8(static_cast<uint8_t>(d.reason));
  } else if (code == Err::Internal && !d.message.empty()) {
    w.str(truncateUtf8(d.message, 120), 120);
  } else if (code == Err::BadImage) {
    w.u8(static_cast<uint8_t>(d.badImage));
  }
  return w.take();
}

Bytes encodeJobEvent(uint8_t attempt, JobStep step, uint32_t atSec) {
  Writer w;
  w.u8(kEvent).u8(static_cast<uint8_t>(Event::Job)).u8(attempt).u8(static_cast<uint8_t>(step)).u32(atSec);
  return w.take();
}

Bytes encodeConfigEvent(uint32_t configRev) {
  Writer w;
  w.u8(kEvent).u8(static_cast<uint8_t>(Event::Config)).u32(configRev);
  return w.take();
}

Bytes encodeTestEvent(TestStep step, bool ok, const std::string& error) {
  Writer w;
  w.u8(kEvent).u8(static_cast<uint8_t>(Event::Test)).u8(static_cast<uint8_t>(step)).u8(ok ? 1 : 0).str(truncateUtf8(error, 120), 120);
  return w.take();
}

Bytes encodeLogEvent(const LogLine& line) {
  Writer w;
  w.u8(kEvent).u8(static_cast<uint8_t>(Event::Log));
  writeLine(w, line);
  return w.take();
}

Bytes encodeFwEvent(FwState state, uint32_t offset, BadImage reason) {
  Writer w;
  w.u8(kEvent).u8(static_cast<uint8_t>(Event::Fw)).u8(static_cast<uint8_t>(state)).u32(offset).u8(static_cast<uint8_t>(reason));
  return w.take();
}

Bytes encodeStatus(const StatusValue& s) {
  Writer w;
  w.u8(s.flags).u8(static_cast<uint8_t>(s.phase)).u32(s.configRev).u32(s.nextEventAt).u8(static_cast<uint8_t>(s.nextEventType));
  w.u8(s.attemptsRemaining).u8(s.nextAttempt);
  return w.take();
}

Bytes encodeDeviceInfo(const DeviceInfo& d) {
  Writer w;
  w.u8(d.v).raw(d.id, 3).u8(static_cast<uint8_t>(d.cam)).u8(d.cams).str(d.fw, 255).str(d.name, kMaxName);
  if (d.update) w.u8(d.caps).u8(d.chip).u32(d.slot);
  return w.take();
}

bool parseDeviceId(const std::string& hex, uint8_t out[3]) {
  if (hex.size() != 6) return false;
  Bytes b;
  if (!fromHex(hex, b) || b.size() != 3) return false;
  std::memcpy(out, b.data(), 3);
  return true;
}

std::string formatDeviceId(const uint8_t id[3]) {
  std::string h = toHex(id, 3);
  for (auto& c : h) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
  return h;
}

std::string advertisedName(const std::string& name, const std::string& deviceId) {
  if (name.empty()) return deviceId;
  return truncateUtf8(name, 22) + "-" + deviceId;
}

}  // namespace kairos
