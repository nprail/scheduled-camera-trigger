#include "link.h"

namespace kairos {

namespace {
constexpr size_t kMaxQueuedEvents = 50;
/// Between messages the core task gets back to the schedule.
constexpr size_t kMaxMessagesPerFlush = 8;
}

void Link::sendError(uint8_t id, Err code) { enqueue(encodeError(id, code), false, id); }

void Link::onWrite(const uint8_t* data, size_t len, uint64_t nowMs) {
  FrameError error = FrameError::None;
  bool complete = reassembler_.push(data, len, error);
  lastFragmentAt_ = nowMs;

  if (error != FrameError::None) {
    sendError(0, error == FrameError::TooLarge ? Err::TooLarge : Err::BadRequest);
    return;
  }
  if (!complete) return;

  Bytes message = reassembler_.take();
  Request req;
  uint8_t id = 0;
  if (!decodeRequest(message, req, id)) {
    sendError(id, Err::BadRequest);
    return;
  }
  Reply reply = dispatcher_.handle(session_, req);
  enqueue(std::move(reply.message), false, req.id);
  for (auto& e : reply.events) pushEvent(e);
}

void Link::tick(uint64_t nowMs) {
  if (reassembler_.pending() && nowMs - lastFragmentAt_ > reassemblyTimeoutMs_) {
    reassembler_.reset();
    sendError(0, Err::BadRequest);
  }
  flush();
}

void Link::pushEvent(const Bytes& event) {
  if (!session_.authed) return;
  enqueue(event, true);
}

void Link::enqueue(Bytes message, bool isEvent, uint8_t id) {
  if (message.size() > kMaxMessage) {
    if (isEvent) return;
    message = encodeError(id, Err::TooLarge);
  }
  if (isEvent) {
    // under backpressure the oldest events are the least useful
    size_t events = 0;
    for (const auto& q : queue_) events += q.isEvent ? 1 : 0;
    if (events >= kMaxQueuedEvents) {
      for (auto it = queue_.begin(); it != queue_.end(); ++it) {
        if (it->isEvent) {
          queue_.erase(it);
          break;
        }
      }
    }
  }
  queue_.push_back(Item{std::move(message), isEvent});
}

size_t Link::flush() {
  size_t sent = 0;
  size_t messages = 0;
  while (!queue_.empty() && messages < kMaxMessagesPerFlush) {
    Item item = std::move(queue_.front());
    queue_.pop_front();
    messages++;
    for (const Bytes& f : fragment(item.message, maxFragment_())) {
      if (!send_(f)) {
        // not subscribed, or the link is stuck: a cut message would corrupt the next one, so drop it all
        queue_.clear();
        return sent;
      }
      sent++;
    }
  }
  return sent;
}

}  // namespace kairos
