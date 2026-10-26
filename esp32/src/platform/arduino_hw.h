// GPIO and random numbers for the camera drivers and the protocol.
#pragma once
#include <Arduino.h>
#include <esp_random.h>

#include <kairos.h>

class ArduinoGpio : public kairos::Gpio {
 public:
  void output(uint8_t pin) override {
    digitalWrite(pin, LOW);  // inactive before the pin becomes an output: no accidental shutter
    pinMode(pin, OUTPUT);
  }
  void write(uint8_t pin, bool high) override { digitalWrite(pin, high ? HIGH : LOW); }
  void release(uint8_t pin) override {
    digitalWrite(pin, LOW);
    pinMode(pin, INPUT_PULLDOWN);
  }
  void delayMs(uint32_t ms) override { vTaskDelay(pdMS_TO_TICKS(ms)); }
};

class EspRandom : public kairos::Random {
 public:
  /// A true random source while the radio is on (BLE is always on here).
  void fill(uint8_t* out, size_t n) override { esp_fill_random(out, n); }
};
