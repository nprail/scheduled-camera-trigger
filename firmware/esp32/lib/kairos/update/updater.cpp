#include "updater.h"

#include <algorithm>
#include <cstring>

namespace kairos {

namespace {
constexpr uint8_t kImageMagic = 0xE9;
constexpr size_t kChipIdOffset = 12;
constexpr size_t kAppDescOffset = 32;
constexpr uint32_t kAppDescMagic = 0xABCD5432;

FwResult refuse(Err err, BadImage reason = BadImage::None) {
  FwResult r;
  r.ok = false;
  r.err = err;
  r.reason = reason;
  return r;
}

bool allZero(const uint8_t* p, size_t n) {
  for (size_t i = 0; i < n; i++) {
    if (p[i]) return false;
  }
  return true;
}
}  // namespace

FwResult Updater::begin(uint32_t size, const uint8_t sha256[32], const uint8_t sig[64], uint64_t nowMs) {
  const FwGate gate = gate_();
  if (!gate.idle || gate.secondsToNextEvent < kFwMinGapSec) return refuse(Err::Busy);
  if (size > flash_.capacity()) return refuse(Err::TooLarge);
  if (size < config_.minSize || size < kFwHeaderLen) return refuse(Err::BadImage, BadImage::Size);

  if (allZero(sig, 64)) {
    if (!config_.allowUnsigned) return refuse(Err::BadImage, BadImage::SignatureMissing);
  } else if (!verifier_.verify(sha256, sig)) {
    return refuse(Err::BadImage, BadImage::SignatureInvalid);
  }

  FwResult out;
  out.value = kFwWindow;
  if (active() && size == size_ && std::memcmp(sha256, sha_, 32) == 0) {  // resume
    lastActivity_ = nowMs;
    out.offset = received_;
    return out;
  }
  abort();

  if (!flash_.begin(size)) return refuse(Err::BadImage, BadImage::Write);
  open_ = true;
  state_ = FwState::Receiving;
  reason_ = BadImage::None;
  size_ = size;
  received_ = 0;
  reported_ = 0;
  std::memcpy(sha_, sha256, 32);
  hash_.reset();
  lastActivity_ = nowMs;
  progressPending_ = false;
  out.offset = 0;
  return out;
}

void Updater::abort() {
  if (open_) flash_.abort();
  open_ = false;
  state_ = FwState::Failed;
  reason_ = BadImage::None;
  progressPending_ = false;
}

void Updater::fail(BadImage reason) {
  if (open_) flash_.abort();
  open_ = false;
  state_ = FwState::Failed;
  reason_ = reason;
  requestProgress();
}

bool Updater::checkHeader() {
  if (head_[0] != kImageMagic) {
    fail(BadImage::Header);
    return false;
  }
  const uint16_t chip = static_cast<uint16_t>(head_[kChipIdOffset] | (head_[kChipIdOffset + 1] << 8));
  if (chip != config_.headerChipId) {
    fail(BadImage::Chip);
    return false;
  }
  uint32_t magic = 0;
  for (int i = 3; i >= 0; i--) magic = (magic << 8) | head_[kAppDescOffset + i];
  if (magic != kAppDescMagic) {  // for example the factory image, which starts with the bootloader
    fail(BadImage::Header);
    return false;
  }
  return true;
}

void Updater::onData(const uint8_t* msg, size_t len, uint64_t nowMs) {
  if (!active() || len < 5) return;
  uint32_t offset = 0;
  for (int i = 3; i >= 0; i--) offset = (offset << 8) | msg[i];
  const uint8_t* data = msg + 4;
  size_t n = len - 4;
  lastActivity_ = nowMs;

  if (offset > received_) {  // a write was lost: tell the phone where to resume
    requestProgress();
    return;
  }
  if (offset < received_) {  // a repeat of bytes already stored
    const size_t skip = received_ - offset;
    if (skip >= n) return;
    data += skip;
    n -= skip;
  }
  if (static_cast<uint64_t>(received_) + n > size_) {
    fail(BadImage::Size);
    return;
  }

  size_t pos = 0;
  while (pos < n) {
    if (received_ < kFwHeaderLen) {
      const size_t take = std::min<size_t>(n - pos, kFwHeaderLen - received_);
      std::memcpy(head_ + received_, data + pos, take);
      hash_.update(data + pos, take);
      received_ += static_cast<uint32_t>(take);
      pos += take;
      if (received_ == kFwHeaderLen) {
        if (!checkHeader()) return;
        if (!flash_.write(head_, kFwHeaderLen)) {
          fail(BadImage::Write);
          return;
        }
      }
    } else {
      hash_.update(data + pos, n - pos);
      if (!flash_.write(data + pos, n - pos)) {
        fail(BadImage::Write);
        return;
      }
      received_ += static_cast<uint32_t>(n - pos);
      pos = n;
    }
  }

  if (received_ == size_ || received_ - reported_ >= kFwWindow / 4) requestProgress();
}

FwResult Updater::end(uint64_t nowMs) {
  if (!active()) return refuse(Err::Busy);
  if (received_ != size_) return refuse(Err::BadImage, BadImage::Incomplete);  // the transfer stays open
  state_ = FwState::Verifying;
  uint8_t digest[32];
  hash_.final(digest);
  if (std::memcmp(digest, sha_, 32) != 0) {
    fail(BadImage::Hash);
    return refuse(Err::BadImage, BadImage::Hash);
  }
  if (!flash_.finish()) {
    fail(BadImage::Header);
    return refuse(Err::BadImage, BadImage::Header);
  }
  if (!flash_.activate()) {
    fail(BadImage::Write);
    return refuse(Err::BadImage, BadImage::Write);
  }
  open_ = false;
  state_ = FwState::Ready;
  readyAt_ = nowMs;
  requestProgress();
  FwResult out;
  out.value = static_cast<uint16_t>(kFwRebootDelayMs);
  return out;
}

void Updater::tick(uint64_t nowMs) {
  if (!active()) return;
  if (nowMs - lastActivity_ > kFwIdleTimeoutMs) {
    fail(BadImage::Interrupted);
    return;
  }
  const FwGate gate = gate_();
  if (!gate.idle || gate.secondsToNextEvent < kFwAbortGapSec) fail(BadImage::Interrupted);
}

bool Updater::takeProgress(FwProgress& out) {
  if (!progressPending_) return false;
  progressPending_ = false;
  out.state = state_;
  out.offset = received_;
  out.reason = reason_;
  if (state_ == FwState::Receiving) reported_ = received_;
  return true;
}

}  // namespace kairos
