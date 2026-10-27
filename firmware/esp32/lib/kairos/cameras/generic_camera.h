// A camera with a standard remote trigger port, driven by two GPIO lines (a port of trigger/cameras/generic.js).
#pragma once
#include "../scheduler/camera.h"
#include "../scheduler/ram_log.h"

namespace kairos {

class Gpio {
 public:
  virtual ~Gpio() = default;
  /// Configure a pin as an output, driven low (inactive).
  virtual void output(uint8_t pin) = 0;
  virtual void write(uint8_t pin, bool high) = 0;
  /// Return a pin to its idle state.
  virtual void release(uint8_t pin) = 0;
  virtual void delayMs(uint32_t ms) = 0;
};

/// How long a pulse holds the line. TBD: settle it against the real cameras during bring-up
/// (the Pi writes high then low back to back). One constant, in one place.
constexpr uint32_t kTriggerPulseMs = 100;

class GenericCamera : public Camera {
 public:
  GenericCamera(Gpio& gpio, RamLog& log, uint8_t releasePin, uint8_t focusPin);
  ~GenericCamera() override;

  void wake() override;
  void start() override;
  void stop() override;
  void sleep() override {}
  bool recording() const override { return recording_; }
  bool ready() const override { return ready_; }

 private:
  void pulse(uint8_t pin);
  void trigger();
  Gpio& gpio_;
  RamLog& log_;
  uint8_t release_;
  uint8_t focus_;  // the release pin when no focus pin is set
  bool recording_ = false;
  bool ready_ = false;
};

}  // namespace kairos
