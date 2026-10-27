// A ring of the last 500 log lines, each with a sequence number (docs/ble-protocol.md, logs.get).
#pragma once
#include <deque>
#include <functional>
#include <string>
#include <vector>

#include "../protocol/messages.h"
#include "clock.h"

namespace kairos {

class RamLog {
 public:
  explicit RamLog(Clock& clock, size_t capacity = 500) : clock_(clock), capacity_(capacity) {}

  void log(const std::string& ns, const std::string& msg) {
    LogLine l;
    l.seq = ++seq_;
    l.ts = static_cast<uint32_t>(clock_.nowMs() / 1000);
    l.ns = truncateUtf8(ns, 8);
    l.msg = truncateUtf8(msg, 120);
    if (sink_) sink_(l);
    lines_.push_back(std::move(l));
    while (lines_.size() > capacity_) lines_.pop_front();
  }

  /// Lines with seq > after, oldest first.
  std::vector<LogLine> since(uint32_t after, size_t limit) const {
    std::vector<LogLine> out;
    for (const auto& l : lines_) {
      if (l.seq > after) {
        out.push_back(l);
        if (out.size() >= limit) break;
      }
    }
    return out;
  }

  uint32_t seq() const { return seq_; }
  /// Called for every line as it is logged (the platform prints it to the serial port).
  void setSink(std::function<void(const LogLine&)> sink) { sink_ = std::move(sink); }

 private:
  Clock& clock_;
  size_t capacity_;
  uint32_t seq_ = 0;
  std::deque<LogLine> lines_;
  std::function<void(const LogLine&)> sink_;
};

}  // namespace kairos
