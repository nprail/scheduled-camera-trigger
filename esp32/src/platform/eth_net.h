// Wired Ethernet to the Z CAM over a W5500 (SPI), and the HTTP client the Z CAM driver uses.
// Wi-Fi is never started: the radio stays off.
#pragma once
#include <Arduino.h>
#include <ETH.h>
#include <Network.h>

#include <kairos.h>

class EthNet : public kairos::Http {
 public:
  /// Start the W5500 and DHCP.
  void begin();
  /// The camera's address (for the static fallback when DHCP gets no lease).
  void setCameraIp(const uint8_t ip[4]);
  /// Call regularly: switches to a static address in the camera's /24 if no lease arrives within 10 s of the link coming up.
  void loop(uint32_t nowMs);

  bool linkUp() override;
  kairos::HttpResult get(const std::string& host, const std::string& path, uint32_t timeoutMs) override;

 private:
  static void onEvent(arduino_event_id_t event);
  uint8_t cameraIp_[4] = {0, 0, 0, 0};
  bool fallbackTried_ = false;
};
