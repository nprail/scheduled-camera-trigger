// The idle app slot, written through esp_ota_ops (docs/ble-protocol.md, fw.*).
#pragma once
#include <esp_ota_ops.h>

#include <kairos.h>

class OtaFlash : public kairos::FlashSink {
 public:
  size_t capacity() override;
  bool begin(size_t size) override;
  bool write(const uint8_t* data, size_t len) override;
  bool finish() override;
  bool activate() override;
  void abort() override;

 private:
  const esp_partition_t* part_ = nullptr;
  esp_ota_handle_t handle_ = 0;
  bool open_ = false;
};
