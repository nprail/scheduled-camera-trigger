// Board wiring for the ESP32-S3 DevKitC-1 (verify against your board).
//
// The GPIO pins are constants, not settings: they are not part of the protocol and no phone can change them.
// To wire a different board, edit the numbers below and rebuild. Keep clear of the strapping
// pins (0, 3, 45, 46), USB (19, 20) and flash/PSRAM (26 to 37). `kNoPin` (0xFF) means "not wired".
#pragma once
#include <stdint.h>

#include <kairos.h>

// W5500 Ethernet (SPI2)
constexpr int KAIROS_W5500_SCLK = 12;
constexpr int KAIROS_W5500_MOSI = 11;
constexpr int KAIROS_W5500_MISO = 13;
constexpr int KAIROS_W5500_CS = 10;
constexpr int KAIROS_W5500_INT = 14;
constexpr int KAIROS_W5500_RST = 9;

// DS3231 RTC (I2C)
constexpr int KAIROS_RTC_SDA = 8;
constexpr int KAIROS_RTC_SCL = 18;

// Pairing button (active high, internal pull-down) and the generic camera's lines.
// The focus pin may be kairos::kNoPin: wake then pulses the release line.
constexpr uint8_t KAIROS_BUTTON_PIN = 4;
constexpr uint8_t KAIROS_RELEASE_PIN = 5;
constexpr uint8_t KAIROS_FOCUS_PIN = 6;

static_assert(KAIROS_BUTTON_PIN != KAIROS_RELEASE_PIN && KAIROS_BUTTON_PIN != KAIROS_FOCUS_PIN,
              "the button and the camera lines must be different pins");

#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif
