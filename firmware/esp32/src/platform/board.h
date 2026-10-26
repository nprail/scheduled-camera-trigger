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
// ESP32-C3-DevKitM-1. The C3 has only GPIO 0 to 21, and the strapping pins (2, 8), the USB pins (18, 19),
// the console UART (20, 21) and the flash (12 to 17) are left alone, which leaves these pins, plus 9 for the BOOT button.
// Using the BOOT button for pairing frees GPIO 3 for the W5500's interrupt. RST is not wired (-1: the firmware
// skips the reset), and neither is the focus line: wake then pulses the release line.

// W5500 Ethernet (SPI)
constexpr int KAIROS_W5500_SCLK = 4;
constexpr int KAIROS_W5500_MOSI = 6;
constexpr int KAIROS_W5500_MISO = 5;
constexpr int KAIROS_W5500_CS = 7;
constexpr int KAIROS_W5500_INT = 3;
constexpr int KAIROS_W5500_RST = -1;

// DS3231 RTC (I2C)
constexpr int KAIROS_RTC_SDA = 0;
constexpr int KAIROS_RTC_SCL = 1;

// The pairing button is the board's BOOT button (GPIO 9, to ground with a pull-up). Reading it after boot is fine:
// the strapping only matters while the chip resets, and holding it then enters the flashing mode, as usual.
constexpr uint8_t KAIROS_BUTTON_PIN = 9;
constexpr bool KAIROS_BUTTON_ACTIVE_LOW = true;
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

// The pairing button is the board's BOOT button (GPIO 0, to ground with a pull-up); see the C3 note above.
// The camera lines follow. The focus pin may be kairos::kNoPin: wake then pulses the release line.
constexpr uint8_t KAIROS_BUTTON_PIN = 0;
constexpr bool KAIROS_BUTTON_ACTIVE_LOW = true;
constexpr uint8_t KAIROS_RELEASE_PIN = 5;
constexpr uint8_t KAIROS_FOCUS_PIN = 6;
#endif

static_assert(KAIROS_BUTTON_PIN != KAIROS_RELEASE_PIN && KAIROS_BUTTON_PIN != KAIROS_FOCUS_PIN,
              "the button and the camera lines must be different pins");

#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif
