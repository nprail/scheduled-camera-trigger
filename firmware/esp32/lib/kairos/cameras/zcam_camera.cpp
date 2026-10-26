#include "zcam_camera.h"

#include <cstdlib>

#include "../config/config.h"

namespace kairos {

bool parseZcamReply(const std::string& body, int& code, std::string& msg) {
  size_t c = body.find("\"code\"");
  if (c == std::string::npos) return false;
  size_t colon = body.find(':', c);
  if (colon == std::string::npos) return false;
  code = std::atoi(body.c_str() + colon + 1);
  msg.clear();
  size_t m = body.find("\"msg\"");
  if (m != std::string::npos) {
    size_t q1 = body.find('"', body.find(':', m) + 1);
    if (q1 != std::string::npos) {
      size_t q2 = body.find('"', q1 + 1);
      if (q2 != std::string::npos) msg = body.substr(q1 + 1, q2 - q1 - 1);
    }
  }
  return true;
}

ZCamCamera::ZCamCamera(Http& http, RamLog& log, const uint8_t ip[4]) : http_(http), log_(log), host_(ipToString(ip)) {}

bool ZCamCamera::call(const std::string& path, std::string* msg, bool retry) {
  for (int attempt = 0; attempt < (retry ? 2 : 1); attempt++) {
    HttpResult r = http_.get(host_, path, kZcamTimeoutMs);
    int code = -1;
    std::string text;
    if (r.ok && parseZcamReply(r.body, code, text) && code == 0) {
      if (msg) *msg = text;
      return true;
    }
    log_.log("ZCAM", r.ok ? (text.empty() ? "Camera reported an error" : text) : "No answer from the camera");
  }
  return false;
}

void ZCamCamera::wake() {
  if (call("/ctrl/session")) log_.log("ZCAM", "Wake");
}

void ZCamCamera::sleep() {
  log_.log("ZCAM", "Sleep");
  call("/ctrl/mode?action=to_standby");
}

void ZCamCamera::start() {
  std::string minutes;
  call("/ctrl/rec?action=remain", &minutes);
  log_.log("ZCAM", "Start Recording, " + minutes + " min left");
  if (call("/ctrl/rec?action=start", nullptr, true)) recording_ = true;
}

void ZCamCamera::stop() {
  if (call("/ctrl/rec?action=stop", nullptr, true)) recording_ = false;
  std::string minutes;
  call("/ctrl/rec?action=remain", &minutes);
  log_.log("ZCAM", "Stop Recording, " + minutes + " min left");
}

}  // namespace kairos
