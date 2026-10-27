#include "controller.h"

namespace kairos {

void Controller::begin() {
  uint32_t rev = 0;
  Bytes blob;
  bool loaded = false;
  if (storage_.loadConfig(rev, blob) && !blob.empty()) {
    Config c;
    if (decodeConfig(blob.data(), blob.size(), c)) {
      config_ = std::move(c);
      rev_ = rev;
      loaded = true;
    } else {
      // corrupt, or a layout this firmware doesn't know: fall back to the defaults and keep the secret
      log_.log("INDEX", "Stored config is unusable, using defaults");
    }
  }
  if (!loaded) {
    config_ = Config();
    rev_ = 1;
  }
  hasSecret_ = storage_.loadSecret(secret_);
  scheduler_.initialize(config_);
}

bool Controller::secret(uint8_t out[kSecretLen]) {
  if (!hasSecret_) return false;
  std::memcpy(out, secret_, kSecretLen);
  return true;
}

void Controller::setSecret(const uint8_t secret[kSecretLen]) {
  std::memcpy(secret_, secret, kSecretLen);
  hasSecret_ = true;
  storage_.saveSecret(secret_);
}

bool Controller::setTime(uint64_t nowMs, int32_t& offsetMs) {
  if (scheduler_.phase() != Phase::Idle) return false;
  int64_t offset = static_cast<int64_t>(clock_.nowMs()) - static_cast<int64_t>(nowMs);
  if (offset > INT32_MAX) offset = INT32_MAX;
  if (offset < INT32_MIN) offset = INT32_MIN;
  offsetMs = static_cast<int32_t>(offset);
  clock_.setNowMs(nowMs);
  scheduler_.reinitialize();
  log_.log("INDEX", "Clock set, offset " + std::to_string(offsetMs) + " ms");
  return true;
}

SaveResult Controller::saveConfig(const Config& c) {
  SaveResult r;
  if (scheduler_.phase() != Phase::Idle) {
    r.status = SaveStatus::Busy;
    return r;
  }
  if (auto err = validateConfig(c, clock_.nowMs())) {
    r.status = SaveStatus::Invalid;
    r.error = *err;
    return r;
  }

  const uint32_t next = rev_ + 1;
  if (!storage_.saveConfig(next, encodeConfig(c))) {
    // keep the old config and schedule untouched
    log_.log("INDEX", "Could not store the config");
    r.status = SaveStatus::Failed;
    return r;
  }
  rev_ = next;
  config_ = c;
  scheduler_.teardown();
  scheduler_.initialize(config_);
  if (configChanged_) configChanged_(rev_);
  r.configRev = rev_;
  return r;
}

std::vector<JobState> Controller::jobStates() {
  std::vector<JobState> out;
  for (const auto& j : scheduler_.jobs()) out.push_back(j.state);
  return out;
}

CameraResult Controller::camera(Op op, bool& recording) {
  if (scheduler_.locked()) return CameraResult::Busy;
  Camera* cam = scheduler_.camera();
  if (!cam) return CameraResult::Unavailable;
  switch (op) {
    case Op::CameraWake: cam->wake(); break;
    case Op::CameraStart: cam->start(); break;
    case Op::CameraStop: cam->stop(); break;
    default: cam->sleep(); break;
  }
  recording = cam->recording();
  return CameraResult::Ok;
}

StatusValue Controller::status() {
  StatusValue s;
  const Camera* cam = scheduler_.camera();
  // a camera that is not reachable only matters while nothing owns it: Stop must stay available mid-recording
  const Phase running = scheduler_.phase();
  s.phase = (cam && !cam->ready() && running == Phase::Idle) ? Phase::Error : running;
  if (cam && cam->recording()) s.flags |= flag::Recording;
  if (cam && cam->ready()) s.flags |= flag::CameraReady;
  if (pairing_.isOpen(clock_.nowMs())) s.flags |= flag::PairingOpen;
  if (clock_.valid()) s.flags |= flag::ClockValid;
  s.configRev = rev_;
  s.attemptsRemaining = static_cast<uint8_t>(scheduler_.attemptsRemaining() > 255 ? 255 : scheduler_.attemptsRemaining());
  NextEvent n = scheduler_.next();
  if (n.valid) {
    s.nextEventAt = static_cast<uint32_t>(n.atMs / 1000);
    s.nextEventType = n.type;
    s.nextAttempt = n.index;
  }
  return s;
}

}  // namespace kairos
