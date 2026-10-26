// The Kairos firmware logic: protocol, config, scheduler and camera drivers. Pure C++ (no Arduino or
// ESP-IDF includes), so it builds and is tested on the host. The radio, Ethernet, RTC and flash live in src/.
#pragma once

#include "protocol/auth.h"
#include "protocol/bytes.h"
#include "protocol/device_api.h"
#include "protocol/dispatcher.h"
#include "protocol/framing.h"
#include "protocol/link.h"
#include "protocol/messages.h"
#include "protocol/protocol.h"
#include "protocol/sha256.h"

#include "config/config.h"

#include "scheduler/camera.h"
#include "scheduler/clock.h"
#include "scheduler/controller.h"
#include "scheduler/ram_log.h"
#include "scheduler/scheduler.h"

#include "cameras/generic_camera.h"
#include "cameras/zcam_camera.h"
