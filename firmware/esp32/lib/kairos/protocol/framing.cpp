#include "framing.h"

namespace kairos {

std::vector<Bytes> fragment(const Bytes& payload, size_t maxFragment) {
  std::vector<Bytes> out;
  if (maxFragment < 4 || payload.size() > 0xffff) return out;
  size_t offset = 0;
  uint8_t idx = 0;
  while (offset < payload.size() || out.empty()) {
    bool first = out.empty();
    size_t header = first ? 3 : 1;
    size_t take = payload.size() - offset;
    if (take > maxFragment - header) take = maxFragment - header;
    Bytes f;
    f.reserve(header + take);
    f.push_back(static_cast<uint8_t>((first ? 0x80 : 0) | (idx & 0x7f)));
    if (first) {
      f.push_back(static_cast<uint8_t>(payload.size() >> 8));  // the length is big-endian
      f.push_back(static_cast<uint8_t>(payload.size() & 0xff));
    }
    f.insert(f.end(), payload.begin() + offset, payload.begin() + offset + take);
    out.push_back(std::move(f));
    offset += take;
    idx = (idx + 1) & 0x7f;
  }
  return out;
}

void Reassembler::reset() {
  total_ = 0;
  nextIdx_ = 0;
  active_ = false;
  discarding_ = false;
  partial_.clear();
}

bool Reassembler::push(const uint8_t* data, size_t len, FrameError& error) {
  error = FrameError::None;
  if (len < 1) { error = FrameError::BadRequest; return false; }
  bool isFirst = (data[0] & 0x80) != 0;
  uint8_t idx = data[0] & 0x7f;

  if (isFirst) {
    reset();
    if (len < 3 || idx != 0) { error = FrameError::BadRequest; return false; }
    size_t total = (static_cast<size_t>(data[1]) << 8) | data[2];
    if (total == 0) { error = FrameError::BadRequest; return false; }
    if (total > maxMessage_) {
      discarding_ = true;  // drop the continuations of this oversized message
      error = FrameError::TooLarge;
      return false;
    }
    total_ = total;
    active_ = true;
    nextIdx_ = 1;
    return append(data + 3, len - 3, error);
  }

  if (discarding_) return false;
  if (!active_ || idx != nextIdx_) {
    reset();
    error = FrameError::BadRequest;
    return false;
  }
  nextIdx_ = (nextIdx_ + 1) & 0x7f;
  return append(data + 1, len - 1, error);
}

bool Reassembler::append(const uint8_t* data, size_t len, FrameError& error) {
  partial_.insert(partial_.end(), data, data + len);
  if (partial_.size() > total_) {
    reset();
    error = FrameError::BadRequest;
    return false;
  }
  if (partial_.size() == total_) {
    message_ = std::move(partial_);
    reset();
    return true;
  }
  return false;
}

}  // namespace kairos
