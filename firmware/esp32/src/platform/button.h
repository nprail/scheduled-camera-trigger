// The pairing button: a press on a GPIO, debounced by 50 ms. Active low (the board's BOOT button: to ground, with a
// pull-up) or active high (to 3.3 V, with a pull-down).
#pragma once
#include <Arduino.h>

class Button {
 public:
  /// Watch `pin`. The internal pull-up (active low) or pull-down (active high) holds it idle until the button is pressed.
  void bind(int pin, bool activeLow);
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
