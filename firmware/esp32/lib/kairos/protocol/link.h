// One BLE connection's protocol state: reassembles Command writes, runs requests through the
// dispatcher, and frames responses and events for the Response characteristic. Pure C++.
//
// It is not thread safe: the firmware calls every method from one task. The radio callbacks only hand
// raw fragments to that task.
#pragma once
#include <deque>
#include <functional>

#include "dispatcher.h"
#include "framing.h"

namespace kairos {

class Link {
 public:
  /// `send` delivers one notification; return false if the client is not subscribed (the message is dropped).
  /// `maxFragment` is the current MTU - 3.
  Link(Dispatcher& dispatcher, std::function<bool(const Bytes&)> send, std::function<size_t()> maxFragment, uint32_t reassemblyTimeoutMs = 5000)
      : dispatcher_(dispatcher), send_(std::move(send)), maxFragment_(std::move(maxFragment)), reassemblyTimeoutMs_(reassemblyTimeoutMs) {}

  Session& session() { return session_; }

  /// A write to the Command characteristic. Runs a complete request to the end before returning.
  void onWrite(const uint8_t* data, size_t len, uint64_t nowMs);

  /// Call periodically: drops a stalled partial message (with an error), and sends queued messages.
  void tick(uint64_t nowMs);

  /// Queue an unsolicited event; dropped unless the client is authenticated.
  void pushEvent(const Bytes& event);

  /// Send everything that is queued. Returns the number of fragments sent.
  size_t flush();

  size_t queued() const { return queue_.size(); }

 private:
  struct Item {
    Bytes message;
    bool isEvent;
  };
  void enqueue(Bytes message, bool isEvent, uint8_t id = 0);
  void sendError(uint8_t id, Err code);

  Dispatcher& dispatcher_;
  std::function<bool(const Bytes&)> send_;
  std::function<size_t()> maxFragment_;
  uint32_t reassemblyTimeoutMs_;
  Reassembler reassembler_;
  uint64_t lastFragmentAt_ = 0;
  std::deque<Item> queue_;
  Session session_;
};

}  // namespace kairos
