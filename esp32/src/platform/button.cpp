#include "button.h"

void IRAM_ATTR Button::isr(void* self) {
  Button* b = static_cast<Button*>(self);
  const uint32_t now = millis();
  if (now - b->lastMs_ >= 50) {
    b->lastMs_ = now;
    b->pressed_ = true;
  }
}

void Button::bind(int pin) {
  if (pin == pin_) return;
  if (pin_ >= 0) detachInterrupt(pin_);
  pin_ = pin;
  pressed_ = false;
  if (pin_ < 0) return;
  pinMode(pin_, INPUT_PULLDOWN);
  attachInterruptArg(pin_, &Button::isr, this, RISING);
}
