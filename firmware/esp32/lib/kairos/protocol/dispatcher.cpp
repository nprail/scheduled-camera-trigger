#include "dispatcher.h"

namespace kairos {

Bytes Dispatcher::error(uint8_t id, Err code, ErrorDetail detail) { return encodeError(id, code, detail); }

Reply Dispatcher::handle(Session& s, const Request& q) {
  Reply out;
  const uint8_t id = q.id;
  auto fail = [&](Err code, ErrorDetail d = ErrorDetail()) {
    out.message = error(id, code, d);
    return out;
  };

  if (q.op == Op::Unknown) return fail(Err::NotSupported);

  bool open = q.op == Op::Hello || q.op == Op::Auth || q.op == Op::AuthPair || q.op == Op::TimeGet;
  if (!open && !s.authed) return fail(Err::Unauthorized);

  switch (q.op) {
    case Op::Hello: {
      random_.fill(s.nonce, kNonceLen);
      s.haveNonce = true;
      HelloResp h;
      uint8_t unused[kSecretLen];
      h.paired = api_.secret(unused);
      std::memcpy(h.nonce, s.nonce, kNonceLen);
      h.nowMs = api_.nowMs();
      out.message = encodeHelloResponse(id, h);
      return out;
    }

    case Op::Auth: {
      if (!s.haveNonce) return fail(Err::BadRequest);
      uint64_t now = api_.nowMs();
      uint32_t retry = lockout_.retryAfter(now);
      if (retry > 0) {
        ErrorDetail d;
        d.retryAfter = static_cast<uint16_t>(retry > 0xffff ? 0xffff : retry);
        return fail(Err::LockedOut, d);
      }
      uint8_t key[kSecretLen];
      bool paired = api_.secret(key);
      uint8_t expected[kProofLen];
      bool ok = false;
      if (paired) {
        clientProof(key, s.nonce, q.clientNonce, expected);
        ok = safeEqual(expected, q.proof, kProofLen);
      }
      if (!ok) {
        lockout_.fail(now);
        return fail(Err::Unauthorized);
      }
      lockout_.success();
      s.authed = true;
      uint8_t proof[kProofLen];
      serverProof(key, s.nonce, q.clientNonce, proof);
      out.message = encodeAuthResponse(id, proof);
      return out;
    }

    case Op::AuthPair:
      if (!api_.pairingOpen()) return fail(Err::PairingClosed);
      api_.setSecret(q.secret);
      api_.closePairing();
      lockout_.success();
      s.authed = true;
      out.message = encodeEmptyResponse(id);
      return out;

    case Op::TimeGet:
      out.message = encodeTimeGetResponse(id, api_.nowMs());
      return out;

    case Op::TimeSet: {
      int32_t offset = 0;
      if (!api_.setTime(q.nowMs, offset)) return fail(Err::Busy);
      out.message = encodeTimeSetResponse(id, api_.nowMs(), offset);
      return out;
    }

    case Op::ConfigGet:
      out.message = encodeConfigGetResponse(id, api_.config());
      return out;

    case Op::ConfigSet: {
      SaveResult r = api_.saveConfig(q.config);
      if (r.status == SaveStatus::Busy) return fail(Err::Busy);
      if (r.status == SaveStatus::Failed) {
        ErrorDetail d;
        d.message = "Could not store the config";
        return fail(Err::Internal, d);
      }
      if (r.status == SaveStatus::Invalid) {
        ErrorDetail d;
        d.field = r.error.field;
        d.index = r.error.index;
        d.reason = r.error.reason;
        return fail(Err::InvalidConfig, d);
      }
      out.message = encodeConfigSetResponse(id, r.configRev);
      return out;
    }

    case Op::JobsGet:
      out.message = encodeJobsResponse(id, api_.jobStates());
      return out;

    case Op::CameraWake:
    case Op::CameraStart:
    case Op::CameraStop:
    case Op::CameraSleep: {
      bool recording = false;
      CameraResult r = api_.camera(q.op, recording);
      if (r == CameraResult::Busy) return fail(Err::Busy);
      if (r == CameraResult::Unavailable) {
        ErrorDetail d;
        d.message = "Camera is not available";
        return fail(Err::Internal, d);
      }
      out.message = encodeCameraResponse(id, recording);
      return out;
    }

    case Op::TestRun:
      if (!api_.startTest()) return fail(Err::Busy);
      out.message = encodeEmptyResponse(id);
      return out;

    case Op::LogsGet: {
      size_t limit = q.limit ? q.limit : 20;
      if (limit > 100) limit = 100;
      std::vector<LogLine> candidates = api_.logsSince(q.after, limit + 1);
      std::vector<LogLine> lines;
      size_t size = 0;
      for (size_t i = 0; i < candidates.size() && i < limit; i++) {
        size += 10 + candidates[i].ns.size() + candidates[i].msg.size();
        if (size > kMaxMessage - 64) break;
        lines.push_back(candidates[i]);
      }
      uint32_t last = lines.empty() ? q.after : lines.back().seq;
      out.message = encodeLogsGetResponse(id, last, candidates.size() > lines.size(), lines);
      return out;
    }

    case Op::LogsSubscribe:
      s.logSub = q.enabled;
      out.message = encodeEmptyResponse(id);
      if (s.logSub) {
        for (const auto& l : api_.logsSince(q.after, 100)) out.events.push_back(encodeLogEvent(l));
      }
      return out;

    case Op::FwBegin:
    case Op::FwEnd:
    case Op::FwAbort: {
      if (!fw_) return fail(Err::NotSupported);
      const uint64_t now = fwClock_ ? fwClock_() : api_.nowMs();
      FwResult r;
      if (q.op == Op::FwBegin) {
        r = fw_->begin(q.fwSize, q.fwSha, q.fwSig, now);
      } else if (q.op == Op::FwEnd) {
        r = fw_->end(now);
      } else {
        fw_->abort();
        out.message = encodeEmptyResponse(id);
        return out;
      }
      if (!r.ok) {
        ErrorDetail d;
        d.badImage = r.reason;
        return fail(r.err, d);
      }
      out.message = q.op == Op::FwBegin ? encodeFwBeginResponse(id, r.offset, r.value) : encodeFwEndResponse(id, r.value);
      return out;
    }

    default:
      return fail(Err::NotSupported);
  }
}

}  // namespace kairos
