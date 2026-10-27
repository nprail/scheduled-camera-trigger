#include "generic_camera.h"

namespace kairos {

GenericCamera::GenericCamera(Gpio& gpio, RamLog& log, uint8_t releasePin, uint8_t focusPin)
    : gpio_(gpio), log_(log), release_(releasePin), focus_(focusPin == kNoPin ? releasePin : focusPin) {
  if (release_ == kNoPin) {
    log_.log("GENERIC", "Camera configuration not set properly");
    return;
  }
  gpio_.output(release_);
  if (focus_ != release_) gpio_.output(focus_);
  ready_ = true;
}

GenericCamera::~GenericCamera() {
  if (release_ == kNoPin) return;
  if (focus_ != release_) gpio_.release(focus_);
  gpio_.release(release_);
}

void GenericCamera::pulse(uint8_t pin) {
  gpio_.write(pin, true);
  gpio_.delayMs(kTriggerPulseMs);
  gpio_.write(pin, false);
}

void GenericCamera::wake() {
  if (!ready_) return;
  log_.log("GENERIC", "Wake");
  pulse(focus_);
}

void GenericCamera::trigger() {
  log_.log("GENERIC", recording_ ? "Stop" : "Record");
  pulse(release_);
  recording_ = !recording_;
}

void GenericCamera::start() {
  if (!ready_ || recording_) return;
  trigger();
}

void GenericCamera::stop() {
  if (!ready_ || !recording_) return;
  trigger();
}

}  // namespace kairos
