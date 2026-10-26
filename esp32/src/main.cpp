// Kairos firmware for an ESP32-S3: wires the protocol, scheduler and camera drivers (lib/kairos) to the
// radio, Ethernet, RTC and flash (src/platform). See docs/esp32-port.md.
//
// One task ("core") owns all device state: BLE writes, scheduled steps and notifications run in turn on
// it, so there are no locks. The NimBLE callbacks only post to a queue. A slow Z CAM call delays the
// others for a few seconds at most (the HTTP timeouts), which the schedule's timings leave room for.
#include <Arduino.h>
#include <esp_mac.h>
#include <esp_system.h>
#include <esp_task_wdt.h>

#include <kairos.h>

#include "platform/arduino_hw.h"
#include "platform/ble_server.h"
#include "platform/board.h"
#include "platform/button.h"
#include "platform/eth_net.h"
#include "platform/nvs_storage.h"
#include "platform/rtc_ds3231.h"
#include "platform/system_clock.h"

using namespace kairos;

namespace {

Ds3231 rtc;
SystemClock clk(rtc);
NvsStorage storage;
RamLog rlog(clk);
ArduinoGpio gpio;
EspRandom rng;
EthNet eth;
BleServer ble;
Button button;
QueueHandle_t bleEvents = nullptr;

uint8_t g_id[3] = {0, 0, 0};
std::unique_ptr<Link> g_link;

constexpr uint32_t kDisciplineEveryMs = 10 * 60 * 1000;
constexpr uint32_t kStatusMinMs = 100;
constexpr uint32_t kStatusRecordingMs = 1000;

/// Scheduler steps and test progress are pushed to the connected phone.
class Hooks : public SchedulerListener {
 public:
  void onJob(uint8_t index, JobStep step, uint32_t atSec) override {
    if (g_link) g_link->pushEvent(encodeJobEvent(index, step, atSec));
  }
  void onTest(TestStep step, bool ok, const std::string& error) override {
    if (g_link) g_link->pushEvent(encodeTestEvent(step, ok, error));
  }
} hooks;

std::unique_ptr<Camera> makeCamera(const Config& c) {
  if (c.cameraType == CameraType::ZCam) {
    eth.setCameraIp(c.cameraIp);
    return std::unique_ptr<Camera>(new ZCamCamera(eth, rlog, c.cameraIp));
  }
  return std::unique_ptr<Camera>(new GenericCamera(gpio, rlog, KAIROS_RELEASE_PIN, KAIROS_FOCUS_PIN));
}

Scheduler scheduler(clk, rlog, makeCamera, &hooks);
Controller controller(clk, storage, rlog, scheduler);
Dispatcher dispatcher(controller, rng);

const char* resetReason() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return "power on";
    case ESP_RST_SW: return "software";
    case ESP_RST_PANIC: return "panic";
    case ESP_RST_INT_WDT: return "interrupt watchdog";
    case ESP_RST_TASK_WDT: return "task watchdog";
    case ESP_RST_WDT: return "watchdog";
    case ESP_RST_BROWNOUT: return "brownout";
    case ESP_RST_DEEPSLEEP: return "deep sleep";
    default: return "unknown";
  }
}

std::string deviceId() { return formatDeviceId(g_id); }

Bytes deviceInfoBytes() {
  const Config& c = controller.currentConfig();
  DeviceInfo d;
  memcpy(d.id, g_id, 3);
  d.cam = c.cameraType;
  d.cams = eth.present() ? (kCamGeneric | kCamZcam) : kCamGeneric;
  d.fw = FW_VERSION;
  d.name = c.name;
  return encodeDeviceInfo(d);
}

/// Name and camera address can change with a config save.
void applyConfig() {
  const Config& c = controller.currentConfig();
  ble.setDeviceInfo(deviceInfoBytes());
  ble.setAdvertisedName(advertisedName(c.name, deviceId()));
  if (c.cameraType == CameraType::ZCam) eth.setCameraIp(c.cameraIp);
}

void handleBle(const BleMessage& m) {
  switch (m.type) {
    case BleMessage::Connected:
      g_link.reset(new Link(
          dispatcher, [](const Bytes& f) { return ble.notify(f); }, []() { return ble.maxFragment(); }));
      rlog.log("BLE", "Connected");
      break;
    case BleMessage::Disconnected:
      g_link.reset();
      rlog.log("BLE", "Disconnected");
      break;
    case BleMessage::Write:
      if (g_link) g_link->onWrite(m.data, m.len, millis());  // the link's timeouts use the monotonic clock
      break;
  }
}

void coreTask(void*) {
  esp_task_wdt_add(nullptr);
  static BleMessage item;
  Bytes lastStatus;
  uint32_t lastStatusAt = 0;
  uint32_t lastLogSeq = rlog.seq();
  uint32_t lastDiscipline = millis();

  for (;;) {
    esp_task_wdt_reset();

    // sleep until something arrives or the next scheduled step is due (at most 100 ms)
    const uint64_t now = clk.nowMs();
    const uint64_t due = scheduler.nextDueMs();
    uint32_t waitMs = 100;
    if (due != 0 && due > now && due - now < waitMs) waitMs = static_cast<uint32_t>(due - now);
    if (xQueueReceive(bleEvents, &item, pdMS_TO_TICKS(waitMs)) == pdTRUE) {
      handleBle(item);
      while (xQueueReceive(bleEvents, &item, 0) == pdTRUE) handleBle(item);
    }

    scheduler.tick();

    if (button.consume()) controller.openPairing();

    eth.loop(millis());

    // push new log lines to a subscribed phone
    if (g_link && g_link->session().logSub) {
      for (const LogLine& l : rlog.since(lastLogSeq, 20)) {
        g_link->pushEvent(encodeLogEvent(l));
        lastLogSeq = l.seq;  // anything beyond 20 goes out on the next pass
      }
    } else {
      lastLogSeq = rlog.seq();
    }

    // Status: on every change, at most once a second while recording
    const StatusValue s = controller.status();
    const Bytes status = encodeStatus(s);
    const uint32_t ms = millis();
    const uint32_t minGap = s.phase == Phase::Recording ? kStatusRecordingMs : kStatusMinMs;
    if (status != lastStatus && ms - lastStatusAt >= minGap) {
      lastStatus = status;
      lastStatusAt = ms;
      ble.setStatus(status);
    }

    if (g_link) g_link->tick(millis());

    // keep the system clock on the RTC while nothing is running
    if (ms - lastDiscipline >= kDisciplineEveryMs && scheduler.phase() == Phase::Idle) {
      lastDiscipline = ms;
      if (clk.discipline()) {
        rlog.log("INDEX", "Clock stepped to the RTC");
        scheduler.reinitialize();
      }
    }
  }
}

}  // namespace

void setup() {
  Serial.begin(115200);

  // the camera lines must be inactive from the first instant: no accidental shutter
  for (uint8_t pin : {KAIROS_RELEASE_PIN, KAIROS_FOCUS_PIN}) {
    if (pin == kNoPin) continue;
    digitalWrite(pin, LOW);
    pinMode(pin, OUTPUT);
  }

  rlog.setSink([](const LogLine& l) { Serial.printf("%lu [%s] %s\n", static_cast<unsigned long>(l.ts), l.ns.c_str(), l.msg.c_str()); });

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);  // the radio's own address: the id never changes across erases
  memcpy(g_id, mac + 3, 3);

  rtc.begin(KAIROS_RTC_SDA, KAIROS_RTC_SCL);
  clk.begin();
  rlog.log("BOOT", std::string("Reset: ") + resetReason());
  if (!rtc.present()) rlog.log("BOOT", "RTC not found");
  if (!clk.valid()) rlog.log("BOOT", "Clock invalid, waiting for a phone");

  controller.onConfigChanged([](uint32_t) { applyConfig(); });
  controller.begin();

  // the Ethernet module is optional: Device Info (read below) says whether this unit can drive a Z CAM
  eth.begin();  // Wi-Fi is never started
  rlog.log("BOOT", eth.present() ? "Ethernet module found" : "No Ethernet module: Z CAM unavailable");

  bleEvents = xQueueCreate(16, sizeof(BleMessage));
  const Config& c = controller.currentConfig();
  ble.begin(advertisedName(c.name, deviceId()), deviceInfoBytes(), bleEvents);
  applyConfig();
  button.bind(KAIROS_BUTTON_PIN);

  // camera calls can block for several seconds (HTTP timeouts), so give the core task a long watchdog
  esp_task_wdt_config_t wdt = {.timeout_ms = 60000, .idle_core_mask = 0, .trigger_panic = true};
  esp_task_wdt_reconfigure(&wdt);

  xTaskCreatePinnedToCore(coreTask, "core", 10240, nullptr, 5, nullptr, portNUM_PROCESSORS > 1 ? 1 : 0);  // the C3 has one core
}

void loop() { vTaskDelete(nullptr); }
