// Receives a firmware image over Bluetooth into the idle app slot (docs/ble-protocol.md, fw.*).
// Pure C++: the flash, the signature check and the schedule are behind small interfaces, so the whole
// transfer (resume, gaps, checks, timeouts) is tested on the host. The real ones are in src/platform/.
#pragma once
#include <functional>

#include "../protocol/bytes.h"
#include "../protocol/protocol.h"
#include "../protocol/sha256.h"

namespace kairos {

/// The bytes the phone may have sent but not yet seen acknowledged.
constexpr uint16_t kFwWindow = 8192;
/// An unfinished transfer is dropped after this long without data (a disconnect included).
constexpr uint32_t kFwIdleTimeoutMs = 120000;
/// A transfer starts only when the next event is at least this far away, and is dropped if one gets closer than kFwAbortGapSec.
constexpr uint32_t kFwMinGapSec = 15 * 60;
constexpr uint32_t kFwAbortGapSec = 2 * 60;
constexpr uint32_t kFwRebootDelayMs = 500;
/// Bytes of the image checked on arrival: the header, the first segment header and the app descriptor magic.
constexpr size_t kFwHeaderLen = 64;
constexpr uint32_t kNoEventSec = 0xFFFFFFFF;

/// The slot the image is written to (an OTA partition on the device).
class FlashSink {
 public:
  virtual ~FlashSink() = default;
  virtual size_t capacity() = 0;
  virtual bool begin(size_t size) = 0;
  virtual bool write(const uint8_t* data, size_t len) = 0;
  /// Check the written image is a valid application image.
  virtual bool finish() = 0;
  /// Make the written slot the one that boots next.
  virtual bool activate() = 0;
  virtual void abort() = 0;
};

/// ECDSA P-256 over a SHA-256 value, against the keys built into the firmware.
class SignatureVerifier {
 public:
  virtual ~SignatureVerifier() = default;
  virtual bool verify(const uint8_t hash[32], const uint8_t sig[64]) = 0;
};

struct FwGate {
  bool idle = true;
  uint32_t secondsToNextEvent = kNoEventSec;  // kNoEventSec when none is scheduled (or the clock is not valid)
};

struct FwConfig {
  uint16_t headerChipId = 0;  // the chip id in an image header that this firmware runs on
  size_t minSize = 0x10000;   // the smallest image a real build can be
  bool allowUnsigned = false;  // developer builds only
};

struct FwProgress {
  FwState state = FwState::Receiving;
  uint32_t offset = 0;
  BadImage reason = BadImage::None;
};

struct FwResult {
  bool ok = true;
  Err err = Err::Internal;                // when !ok
  BadImage reason = BadImage::None;       // when err == BadImage
  uint32_t offset = 0;                    // begin: where to start
  uint16_t value = 0;                     // begin: the window; end: rebootInMs
};

class Updater {
 public:
  Updater(FlashSink& flash, SignatureVerifier& verifier, FwConfig config, std::function<FwGate()> gate)
      : flash_(flash), verifier_(verifier), config_(config), gate_(std::move(gate)) {}

  FwResult begin(uint32_t size, const uint8_t sha256[32], const uint8_t sig[64], uint64_t nowMs);
  FwResult end(uint64_t nowMs);
  void abort();

  /// One write to the Firmware characteristic: `[offset u32][bytes]`.
  void onData(const uint8_t* msg, size_t len, uint64_t nowMs);
  /// Call regularly: drops a transfer that stalled or whose safety conditions no longer hold.
  void tick(uint64_t nowMs);

  /// The next event for the phone, if there is one.
  bool takeProgress(FwProgress& out);
  /// A transfer is open (the device is not free to do other work such as stepping its clock).
  bool active() const { return state_ == FwState::Receiving && open_; }
  /// The image is in place and the device should restart now.
  bool rebootDue(uint64_t nowMs) const { return state_ == FwState::Ready && nowMs - readyAt_ >= kFwRebootDelayMs; }
  uint32_t received() const { return received_; }

 private:
  void fail(BadImage reason);
  bool checkHeader();
  void requestProgress() { progressPending_ = true; }

  FlashSink& flash_;
  SignatureVerifier& verifier_;
  FwConfig config_;
  std::function<FwGate()> gate_;

  bool open_ = false;
  FwState state_ = FwState::Failed;
  BadImage reason_ = BadImage::None;
  uint32_t size_ = 0;
  uint32_t received_ = 0;
  uint32_t reported_ = 0;
  uint8_t sha_[32] = {0};
  Sha256 hash_;
  uint8_t head_[kFwHeaderLen] = {0};
  uint64_t lastActivity_ = 0;
  uint64_t readyAt_ = 0;
  bool progressPending_ = false;
};

}  // namespace kairos
