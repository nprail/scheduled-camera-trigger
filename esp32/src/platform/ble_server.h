// The BLE GATT server (NimBLE-Arduino): Device Info, Status, Command and Response (docs/ble-protocol.md).
// The radio callbacks run on the NimBLE task, so they only post to a queue that the core task reads.
#pragma once
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

#include <atomic>
#include <string>

#include <kairos.h>

struct BleMessage {
  enum Type : uint8_t { Connected, Disconnected, Write };
  Type type;
  uint16_t len;
  uint8_t data[520];  // a write is at most MTU - 3 = 514 bytes
};

class BleServer {
 public:
  /// Start advertising. `events` receives BleMessage items.
  bool begin(const std::string& advertisedName, const kairos::Bytes& deviceInfo, QueueHandle_t events);
  void setDeviceInfo(const kairos::Bytes& deviceInfo);
  void setAdvertisedName(const std::string& name);

  /// Update the Status characteristic, and notify if the phone subscribed.
  void setStatus(const kairos::Bytes& status);
  /// Notify one Response fragment. Returns false if the phone is not subscribed (or gone).
  bool notify(const kairos::Bytes& fragment);
  /// MTU - 3 of the current connection.
  size_t maxFragment() const;
  bool connected() const { return conn_ != kNone; }

  // used by the callbacks
  void onConnect(uint16_t handle);
  void onDisconnect();
  void onMtu(uint16_t mtu) { mtu_ = mtu; }
  void onSubscribe(bool isStatus, bool subscribed) {
    if (isStatus) statusSub_ = subscribed;
    else responseSub_ = subscribed;
  }
  QueueHandle_t queue() const { return queue_; }

 private:
  void startAdvertising();
  static constexpr uint16_t kNone = 0xFFFF;
  std::string name_;
  QueueHandle_t queue_ = nullptr;
  std::atomic<uint16_t> conn_{kNone};
  std::atomic<uint16_t> mtu_{23};
  std::atomic<bool> statusSub_{false};
  std::atomic<bool> responseSub_{false};
};
