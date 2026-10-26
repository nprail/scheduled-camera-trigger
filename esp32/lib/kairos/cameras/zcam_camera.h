// A Z CAM over its HTTP API on the wired Ethernet link (a port of trigger/cameras/zcam.js and lib/zcam-api.js).
#pragma once
#include <string>

#include "../scheduler/camera.h"
#include "../scheduler/ram_log.h"

namespace kairos {

struct HttpResult {
  bool ok = false;  // the request completed
  int status = 0;
  std::string body;
};

class Http {
 public:
  virtual ~Http() = default;
  /// True while the Ethernet link is up and has an address.
  virtual bool linkUp() = 0;
  /// GET http://host/path, with a timeout. Blocks.
  virtual HttpResult get(const std::string& host, const std::string& path, uint32_t timeoutMs) = 0;
};

/// The camera's `{"code":0,"msg":"..."}` reply. Returns false if there is no code.
bool parseZcamReply(const std::string& body, int& code, std::string& msg);

constexpr uint32_t kZcamTimeoutMs = 5000;

class ZCamCamera : public Camera {
 public:
  ZCamCamera(Http& http, RamLog& log, const uint8_t ip[4]);

  void wake() override;
  void start() override;
  void stop() override;
  void sleep() override;
  bool recording() const override { return recording_; }
  /// The Ethernet link is up with an address. A failed call does not latch: the next one may work.
  bool ready() const override { return linkOk(); }

 private:
  bool linkOk() const { return http_.linkUp(); }
  /// GET a /ctrl path. Returns true if the camera answered code 0. Retries once.
  bool call(const std::string& path, std::string* msg = nullptr, bool retry = false);
  Http& http_;
  RamLog& log_;
  std::string host_;
  bool recording_ = false;
};

}  // namespace kairos
