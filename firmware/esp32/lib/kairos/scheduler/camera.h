// The camera contract (the same five calls as the Node drivers). Pure C++.
#pragma once
#include <functional>
#include <memory>

#include "../config/config.h"

namespace kairos {

class Camera {
 public:
  virtual ~Camera() = default;
  virtual void wake() = 0;
  virtual void start() = 0;
  virtual void stop() = 0;
  virtual void sleep() = 0;
  virtual bool recording() const = 0;
  /// False if the camera hardware or connection failed.
  virtual bool ready() const = 0;
};

/// Builds the camera for a config. Returns null if the config can't be used.
using CameraFactory = std::function<std::unique_ptr<Camera>(const Config&)>;

}  // namespace kairos
