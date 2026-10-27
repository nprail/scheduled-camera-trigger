#include "ota_flash.h"

size_t OtaFlash::capacity() {
  const esp_partition_t* p = esp_ota_get_next_update_partition(nullptr);
  return p ? p->size : 0;
}

bool OtaFlash::begin(size_t) {
  abort();
  part_ = esp_ota_get_next_update_partition(nullptr);
  if (!part_) return false;
  // sequential writes: the slot is erased as the image arrives, so begin does not stall the schedule for seconds
  open_ = esp_ota_begin(part_, OTA_WITH_SEQUENTIAL_WRITES, &handle_) == ESP_OK;
  return open_;
}

bool OtaFlash::write(const uint8_t* data, size_t len) { return open_ && esp_ota_write(handle_, data, len) == ESP_OK; }

bool OtaFlash::finish() {
  if (!open_) return false;
  open_ = false;  // esp_ota_end releases the handle whether or not the image is valid
  return esp_ota_end(handle_) == ESP_OK;
}

bool OtaFlash::activate() { return part_ && esp_ota_set_boot_partition(part_) == ESP_OK; }

void OtaFlash::abort() {
  if (open_) esp_ota_abort(handle_);
  open_ = false;
}
