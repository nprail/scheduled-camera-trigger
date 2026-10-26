// The pairing button: a rising edge on a GPIO, debounced by 50 ms.
#pragma once
#include <Arduino.h>

class Button {
 public:
  /// Watch `pin` (the internal pull-down keeps it low until the button pulls it to 3.3 V). Rebinding moves the watch.
  void bind(int pin);
  int pin() const { return pin_; }
  /// True once per press.
  bool consume() {
    if (!pressed_) return false;
    pressed_ = false;
    return true;
  }

 private:
  static void IRAM_ATTR isr(void* self);
  int pin_ = -1;
  volatile bool pressed_ = false;
  volatile uint32_t lastMs_ = 0;
};
