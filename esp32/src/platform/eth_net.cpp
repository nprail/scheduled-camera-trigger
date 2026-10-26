#include "eth_net.h"

#include <ETH.h>
#include <HTTPClient.h>
#include <SPI.h>

#include "board.h"

namespace {
volatile bool g_link = false;      // the cable is connected
volatile bool g_hasIp = false;     // an address (leased or static) is configured
volatile uint32_t g_linkSinceMs = 0;
constexpr uint32_t kDhcpWaitMs = 10000;
}  // namespace

void EthNet::onEvent(arduino_event_id_t event) {
  switch (event) {
    case ARDUINO_EVENT_ETH_CONNECTED:
      g_link = true;
      g_linkSinceMs = millis();
      break;
    case ARDUINO_EVENT_ETH_GOT_IP:
      g_hasIp = true;
      break;
    case ARDUINO_EVENT_ETH_LOST_IP:
      g_hasIp = false;
      break;
    case ARDUINO_EVENT_ETH_DISCONNECTED:
      g_link = false;
      g_hasIp = false;
      break;
    default:
      break;
  }
}

bool EthNet::probe() {
  pinMode(KAIROS_W5500_CS, OUTPUT);
  digitalWrite(KAIROS_W5500_CS, HIGH);
  pinMode(KAIROS_W5500_RST, OUTPUT);
  digitalWrite(KAIROS_W5500_RST, LOW);
  delay(2);
  digitalWrite(KAIROS_W5500_RST, HIGH);
  delay(60);  // the chip's PLL needs about 50 ms after a reset

  // VERSIONR is at 0x0039 in the common register block; a read is address, control byte 0x00, then the data
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));
  digitalWrite(KAIROS_W5500_CS, LOW);
  SPI.transfer(0x00);
  SPI.transfer(0x39);
  SPI.transfer(0x00);
  const uint8_t version = SPI.transfer(0x00);
  digitalWrite(KAIROS_W5500_CS, HIGH);
  SPI.endTransaction();
  return version == 0x04;
}

bool EthNet::begin() {
  SPI.begin(KAIROS_W5500_SCLK, KAIROS_W5500_MISO, KAIROS_W5500_MOSI);
  present_ = probe();
  if (!present_) return false;  // nothing is started: no driver, no events, no DHCP
  Network.onEvent(&EthNet::onEvent);
  ETH.begin(ETH_PHY_W5500, 1, KAIROS_W5500_CS, KAIROS_W5500_INT, KAIROS_W5500_RST, SPI);  // DHCP is the default
  return true;
}

void EthNet::setCameraIp(const uint8_t ip[4]) {
  memcpy(cameraIp_, ip, 4);
  fallbackTried_ = false;
}

void EthNet::loop(uint32_t nowMs) {
  if (!g_link) {
    fallbackTried_ = false;
    return;
  }
  if (g_hasIp || fallbackTried_) return;
  if (nowMs - g_linkSinceMs < kDhcpWaitMs) return;
  fallbackTried_ = true;
  if (!(cameraIp_[0] | cameraIp_[1] | cameraIp_[2] | cameraIp_[3])) return;  // nothing to derive an address from
  // a static address in the camera's /24: .250, or .249 if that is the camera's own address
  const uint8_t host = cameraIp_[3] == 250 ? 249 : 250;
  IPAddress ip(cameraIp_[0], cameraIp_[1], cameraIp_[2], host);
  IPAddress gateway(cameraIp_[0], cameraIp_[1], cameraIp_[2], cameraIp_[3]);
  IPAddress subnet(255, 255, 255, 0);
  if (ETH.config(ip, gateway, subnet)) g_hasIp = true;
}

bool EthNet::linkUp() { return g_link && g_hasIp; }

kairos::HttpResult EthNet::get(const std::string& host, const std::string& path, uint32_t timeoutMs) {
  kairos::HttpResult r;
  if (!linkUp()) return r;
  HTTPClient http;
  http.setConnectTimeout(timeoutMs);
  http.setTimeout(timeoutMs);
  String url = String("http://") + host.c_str() + path.c_str();
  if (!http.begin(url)) return r;
  const int code = http.GET();
  if (code > 0) {
    r.ok = true;
    r.status = code;
    r.body = http.getString().c_str();
  }
  http.end();
  return r;
}
