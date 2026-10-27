// Kairos firmware for an ESP32-S3 or ESP32-C3: wires the protocol, scheduler and camera drivers (lib/kairos) to the
// radio, Ethernet, RTC and flash (src/platform). See docs/esp32-port.md.
//
// One task ("core") owns all device state: BLE writes, scheduled steps and notifications run in turn on
// it, so there are no locks. The NimBLE callbacks only post to a queue. A slow Z CAM call delays the
// others for a few seconds at most (the HTTP timeouts), which the schedule's timings leave room for.
#include <Arduino.h>
#include <esp_mac.h>
#include <esp_ota_ops.h>
#include <esp_rom_sys.h>
#include <esp_system.h>
#include <esp_task_wdt.h>

#include <kairos.h>

#include "platform/arduino_hw.h"
#include "platform/ble_server.h"
#include "platform/board.h"
#include "platform/button.h"
#include "platform/ecdsa_verifier.h"
#include "platform/eth_net.h"
#include "platform/fw_tag.h"
#include "platform/nvs_storage.h"
#include "platform/ota_flash.h"
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

// Firmware updates over Bluetooth (docs/ble-protocol.md, fw.*). The phone starts them; the device never does.
#if CONFIG_IDF_TARGET_ESP32C3
constexpr uint8_t kChip = kChipEsp32C3;
constexpr uint16_t kHeaderChipId = 5;  // ESP_CHIP_ID_ESP32C3
#else
constexpr uint8_t kChip = kChipEsp32S3;
constexpr uint16_t kHeaderChipId = 9;  // ESP_CHIP_ID_ESP32S3
#endif
#ifdef KAIROS_ALLOW_UNSIGNED
constexpr bool kAllowUnsigned = true;  // the device-dev environments only: never built by the release workflow
#else
constexpr bool kAllowUnsigned = false;
#endif
constexpr size_t kFwBufferBytes = kFwWindow + 4096;  // the window plus the per-message overhead
constexpr uint32_t kConfirmAfterMs = 30000;

OtaFlash otaFlash;
EcdsaVerifier verifier;
MessageBufferHandle_t fwBuffer = nullptr;
bool g_pendingVerify = false;  // this image has not confirmed itself yet: a reset before then goes back to the old one
bool g_sessionProven = false;  // a phone has connected and authenticated since boot: Bluetooth works

FwGate fwGate() {
  const StatusValue s = controller.status();
  FwGate g;
  g.idle = s.phase == Phase::Idle;
  if ((s.flags & flag::ClockValid) && s.nextEventType != EventType::None) {
    const uint32_t now = static_cast<uint32_t>(clk.nowMs() / 1000);
    g.secondsToNextEvent = s.nextEventAt > now ? s.nextEventAt - now : 0;
  }
  return g;
}

FwConfig makeFwConfig() {
  FwConfig c;
  c.headerChipId = kHeaderChipId;
  c.allowUnsigned = kAllowUnsigned;
  return c;
}
Updater updater(otaFlash, verifier, makeFwConfig(), fwGate);

// A new image must run for a while before it keeps the slot. verifyRollbackLater() makes the Arduino core
// leave it pending instead of confirming it at once.
extern "C" bool verifyRollbackLater() { return true; }

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
#ifdef ESP_RST_USB
    case ESP_RST_USB: return "usb";
#endif
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
  d.update = true;
  d.caps = kCapFirmwareUpdate;
  d.chip = kChip;
  d.slot = static_cast<uint32_t>(otaFlash.capacity());
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
      ble.tuneConnection();
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

/// Bytes written to the Firmware characteristic go to the updater, from an authenticated phone only.
void handleFirmwareData(uint64_t nowMs) {
  static uint8_t msg[520];
  for (;;) {
    const size_t n = xMessageBufferReceive(fwBuffer, msg, sizeof msg, 0);
    if (n == 0) return;
    if (g_link && g_link->session().authed) updater.onData(msg, n, nowMs);
  }
}

void handleFirmwareProgress() {
  static bool wasActive = false;
  FwProgress p;
  while (updater.takeProgress(p)) {
    if (g_link) g_link->pushEvent(encodeFwEvent(p.state, p.offset, p.reason));
    if (p.state == FwState::Failed) rlog.log("FW", "Update failed (reason " + std::to_string(static_cast<int>(p.reason)) + ")");
    if (p.state == FwState::Ready) rlog.log("FW", "Update verified, restarting");
  }
  if (updater.active() && !wasActive) rlog.log("FW", "Receiving an update");
  wasActive = updater.active();
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
    uint32_t waitMs = updater.active() ? 10 : 100;  // an image arrives in a stream: keep draining it
    if (due != 0 && due > now && due - now < waitMs) waitMs = static_cast<uint32_t>(due - now);
    if (xQueueReceive(bleEvents, &item, pdMS_TO_TICKS(waitMs)) == pdTRUE) {
      handleBle(item);
      while (xQueueReceive(bleEvents, &item, 0) == pdTRUE) handleBle(item);
    }

    scheduler.tick();

    handleFirmwareData(millis());
    updater.tick(millis());
    handleFirmwareProgress();
    if (updater.rebootDue(millis())) {
      vTaskDelay(pdMS_TO_TICKS(100));  // let the last notification and log line out
      esp_restart();
    }
    if (g_link && g_link->session().authed) g_sessionProven = true;
    // A new image keeps its slot only after it has run for a while AND a phone has authenticated over Bluetooth with
    // it: an image whose radio is broken could not be updated or fixed again. If that never happens, a reset
    // (a power cycle) goes back to the previous image.
    if (g_pendingVerify && g_sessionProven && millis() > kConfirmAfterMs) {
      g_pendingVerify = false;
      esp_ota_mark_app_valid_cancel_rollback();
      rlog.log("FW", std::string("Update confirmed: ") + FW_VERSION);
    }

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
    if (ms - lastDiscipline >= kDisciplineEveryMs && scheduler.phase() == Phase::Idle && !updater.active()) {
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
    pinMode(pin, OUTPUT);  // the output register is low after a reset, so the line stays inactive
    digitalWrite(pin, LOW);
  }

  // the ROM printf goes to the console on both a UART bridge and native USB, even before a host opens the port
  rlog.setSink([](const LogLine& l) { esp_rom_printf("%lu [%s] %s\n", static_cast<unsigned long>(l.ts), l.ns.c_str(), l.msg.c_str()); });

  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);  // the radio's own address: the id never changes across erases
  memcpy(g_id, mac + 3, 3);

  rtc.begin(KAIROS_RTC_SDA, KAIROS_RTC_SCL);
  clk.begin();
  rlog.log("BOOT", std::string("Reset: ") + resetReason());
  rlog.log("BOOT", std::string("Firmware ") + kairos_fw_tag.version);
  if (kAllowUnsigned) rlog.log("BOOT", "WARNING: this build accepts unsigned firmware updates (developer build)");
  {
    esp_ota_img_states_t state;
    g_pendingVerify = esp_ota_get_state_partition(esp_ota_get_running_partition(), &state) == ESP_OK && state == ESP_OTA_IMG_PENDING_VERIFY;
    if (g_pendingVerify) rlog.log("FW", "New firmware is pending verification");
  }
  if (!rtc.present()) rlog.log("BOOT", "RTC not found");
  if (!clk.valid()) rlog.log("BOOT", "Clock invalid, waiting for a phone");

  controller.onConfigChanged([](uint32_t) { applyConfig(); });
  controller.begin();

  // the Ethernet module is optional: Device Info (read below) says whether this unit can drive a Z CAM
  eth.begin();  // Wi-Fi is never started
  rlog.log("BOOT", eth.present() ? "Ethernet module found" : "No Ethernet module: Z CAM unavailable");

  bleEvents = xQueueCreate(16, sizeof(BleMessage));
  fwBuffer = xMessageBufferCreate(kFwBufferBytes);
  dispatcher.setFirmware(&updater, []() { return static_cast<uint64_t>(millis()); });
  const Config& c = controller.currentConfig();
  ble.setFirmwareBuffer(fwBuffer);
  ble.begin(advertisedName(c.name, deviceId()), deviceInfoBytes(), bleEvents);
  rlog.log("BLE", "Advertising as " + advertisedName(c.name, deviceId()));
  applyConfig();
  button.bind(KAIROS_BUTTON_PIN, KAIROS_BUTTON_ACTIVE_LOW);

  // camera calls can block for several seconds (HTTP timeouts), so give the core task a long watchdog
  esp_task_wdt_config_t wdt = {.timeout_ms = 60000, .idle_core_mask = 0, .trigger_panic = true};
  esp_task_wdt_reconfigure(&wdt);

  xTaskCreatePinnedToCore(coreTask, "core", 10240, nullptr, 5, nullptr, portNUM_PROCESSORS > 1 ? 1 : 0);  // the C3 has one core
}

void loop() { vTaskDelete(nullptr); }
