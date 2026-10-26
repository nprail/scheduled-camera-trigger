#include "ble_server.h"

#include <NimBLEDevice.h>

using namespace kairos;

namespace {
BleServer* g_self = nullptr;
NimBLECharacteristic* g_deviceInfo = nullptr;
NimBLECharacteristic* g_status = nullptr;
NimBLECharacteristic* g_response = nullptr;

void post(BleMessage::Type type, const uint8_t* data = nullptr, size_t len = 0) {
  static BleMessage m;  // only the NimBLE task posts
  if (len > sizeof(m.data)) return;  // never clip a fragment: the phone's reassembly timeout reports it
  m.type = type;
  m.len = static_cast<uint16_t>(len);
  if (data && m.len) memcpy(m.data, data, m.len);
  // Connected and Disconnected must not be lost behind a full queue (the core task can be busy in an HTTP
  // call for seconds): whatever is queued belongs to the previous connection, so drop it.
  if (type != BleMessage::Write) xQueueReset(g_self->queue());
  xQueueSend(g_self->queue(), &m, 0);
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& info) override {
    g_self->onConnect(info.getConnHandle());
    post(BleMessage::Connected);
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    g_self->onDisconnect();
    post(BleMessage::Disconnected);
  }
  void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override { g_self->onMtu(mtu); }
};

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    NimBLEAttValue v = c->getValue();
    post(BleMessage::Write, v.data(), v.size());
  }
};

class SubscribeCallbacks : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic* c, NimBLEConnInfo&, uint16_t subValue) override {
    g_self->onSubscribe(c == g_status, (subValue & 1) != 0);
  }
};

ServerCallbacks serverCallbacks;
CommandCallbacks commandCallbacks;
SubscribeCallbacks subscribeCallbacks;
}  // namespace

bool BleServer::begin(const std::string& advertisedName, const Bytes& deviceInfo, QueueHandle_t events) {
  g_self = this;
  queue_ = events;
  name_ = advertisedName;

  NimBLEDevice::init(name_);
  NimBLEDevice::setMTU(517);
  // LE Secure Connections with Just Works bonding; the Command characteristic requires an encrypted link
  NimBLEDevice::setSecurityAuth(true, false, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks, false);
  server->advertiseOnDisconnect(false);  // onDisconnect() restarts it, so the name set by the core task is used

  NimBLEService* service = server->createService(kUuidService);
  g_deviceInfo = service->createCharacteristic(kUuidDeviceInfo, NIMBLE_PROPERTY::READ);
  g_status = service->createCharacteristic(kUuidStatus, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  NimBLECharacteristic* command = service->createCharacteristic(
      kUuidCommand, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_ENC);
  g_response = service->createCharacteristic(kUuidResponse, NIMBLE_PROPERTY::NOTIFY);
  command->setCallbacks(&commandCallbacks);
  g_status->setCallbacks(&subscribeCallbacks);
  g_response->setCallbacks(&subscribeCallbacks);

  g_deviceInfo->setValue(deviceInfo.data(), deviceInfo.size());
  Bytes empty(kStatusLen, 0);
  g_status->setValue(empty.data(), empty.size());

  startAdvertising();
  return true;
}

void BleServer::startAdvertising() {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  NimBLEAdvertisementData data;
  data.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  data.addServiceUUID(NimBLEUUID(kUuidService));
  NimBLEAdvertisementData scan;
  scan.setName(name_);  // already limited to 29 bytes by advertisedName()
  adv->setAdvertisementData(data);
  adv->setScanResponseData(scan);
  adv->setMinInterval(1600);  // 1 s, in 0.625 ms units
  adv->setMaxInterval(1600);
  adv->start();
}

void BleServer::setAdvertisedName(const std::string& name) {
  if (name == name_) return;
  name_ = name;
  NimBLEDevice::setDeviceName(name_);
  if (!connected()) {
    NimBLEDevice::getAdvertising()->stop();
    startAdvertising();
  }
}

void BleServer::setDeviceInfo(const Bytes& deviceInfo) { g_deviceInfo->setValue(deviceInfo.data(), deviceInfo.size()); }

void BleServer::setStatus(const Bytes& status) {
  g_status->setValue(status.data(), status.size());
  if (statusSub_ && connected()) g_status->notify(status.data(), status.size(), conn_);
}

bool BleServer::notify(const Bytes& fragment) {
  if (!responseSub_ || !connected()) return false;
  for (int tries = 0; tries < 20; tries++) {  // at most about 200 ms: the core task also runs the schedule
    if (g_response->notify(fragment.data(), fragment.size(), conn_)) {
      vTaskDelay(pdMS_TO_TICKS(10));  // pace fragments: at least one connection event apart
      return true;
    }
    if (!responseSub_ || !connected()) return false;
    vTaskDelay(pdMS_TO_TICKS(10));  // the controller's buffers are full
  }
  return false;
}

size_t BleServer::maxFragment() const {
  const size_t mtu = mtu_;
  return mtu > 3 ? mtu - 3 : 20;
}

void BleServer::onConnect(uint16_t handle) {
  conn_ = handle;
  mtu_ = 23;
  statusSub_ = false;
  responseSub_ = false;
}

void BleServer::onDisconnect() {
  conn_ = kNone;
  statusSub_ = false;
  responseSub_ = false;
  startAdvertising();
}
