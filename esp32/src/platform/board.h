// Board wiring for the ESP32-S3 DevKitC-1 and the ESP32-C3-DevKitM-1 (verify against your board). The chip comes
// from the PlatformIO environment: `device` is the S3, `device-c3` the C3.
//
// The GPIO pins are constants, not settings: they are not part of the protocol and no phone can change them.
// To wire a different board, edit the numbers below and rebuild. Keep clear of the strapping pins (S3: 0, 3, 45, 46;
// C3: 2, 8, 9), USB (S3: 19, 20; C3: 18, 19) and flash/PSRAM (S3: 26 to 37; C3: 12 to 17). `kNoPin` (0xFF) means
// "not wired".
#pragma once
#include <stdint.h>

#include <kairos.h>

#include <sdkconfig.h>

#if CONFIG_IDF_TARGET_ESP32C3
// ESP32-C3-DevKitM-1. The C3 has only GPIO 0 to 21, and the strapping pins (2, 8, 9), the USB pins (18, 19),
// the console UART (20, 21) and the flash (12 to 17) are left alone, which leaves exactly these eight.
// That is why the W5500's INT and RST are not wired here: -1 makes the driver poll the chip, and the
// firmware skips the reset. The focus line is not wired either: wake then pulses the release line.

// W5500 Ethernet (SPI)
constexpr int KAIROS_W5500_SCLK = 4;
constexpr int KAIROS_W5500_MOSI = 6;
constexpr int KAIROS_W5500_MISO = 5;
constexpr int KAIROS_W5500_CS = 7;
constexpr int KAIROS_W5500_INT = -1;
constexpr int KAIROS_W5500_RST = -1;

// DS3231 RTC (I2C)
constexpr int KAIROS_RTC_SDA = 0;
constexpr int KAIROS_RTC_SCL = 1;

constexpr uint8_t KAIROS_BUTTON_PIN = 3;
constexpr uint8_t KAIROS_RELEASE_PIN = 10;
constexpr uint8_t KAIROS_FOCUS_PIN = kairos::kNoPin;
#else
// ESP32-S3-DevKitC-1.

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
#endif

static_assert(KAIROS_BUTTON_PIN != KAIROS_RELEASE_PIN && KAIROS_BUTTON_PIN != KAIROS_FOCUS_PIN,
              "the button and the camera lines must be different pins");

#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif
