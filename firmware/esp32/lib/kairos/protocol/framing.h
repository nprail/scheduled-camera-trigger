// Message framing (docs/ble-protocol.md, section 3). Pure C++.
#pragma once
#include <vector>

#include "bytes.h"
#include "protocol.h"

namespace kairos {

/// Split a message into fragments that each fit one ATT payload (`maxFragment` = MTU - 3, at least 4).
std::vector<Bytes> fragment(const Bytes& payload, size_t maxFragment);

enum class FrameError : uint8_t { None, BadRequest, TooLarge };

/// Reassembles fragments into messages. push() returns true when a message is complete (taken with
/// `take()`); a framing error is reported through `error`. A stalled message is dropped by the caller with reset().
class Reassembler {
 public:
  explicit Reassembler(size_t maxMessage = kMaxMessage) : maxMessage_(maxMessage) {}
  bool push(const uint8_t* data, size_t len, FrameError& error);
  Bytes take() { return std::move(message_); }
  void reset();
  /// True while a partial message is buffered.
  bool pending() const { return active_; }

 private:
  bool append(const uint8_t* data, size_t len, FrameError& error);
  size_t maxMessage_;
  size_t total_ = 0;
  uint8_t nextIdx_ = 0;
  bool active_ = false;
  bool discarding_ = false;
  Bytes partial_;
  Bytes message_;
};

}  // namespace kairos
