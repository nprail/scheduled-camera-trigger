// Plans wake, start and stop for each launch attempt (a port of trigger/lib/scheduler.js).
// All times are absolute UTC milliseconds, re-read from the clock on every tick, so a clock change only
// needs reinitialize(). Pure C++, single threaded: one task calls tick() and everything else.
#pragma once
#include <string>
#include <vector>

#include "camera.h"
#include "clock.h"
#include "ram_log.h"

namespace kairos {

struct Job {
  std::string name;
  JobState state = JobState::Pending;
  bool recording = false;
  uint64_t wakeMs = 0;
  uint64_t recordMs = 0;
  uint64_t launchMs = 0;
  uint64_t stopMs = 0;
};

struct NextEvent {
  bool valid = false;
  EventType type = EventType::None;
  uint64_t atMs = 0;
  uint8_t index = kNoAttempt;
};

class SchedulerListener {
 public:
  virtual ~SchedulerListener() = default;
  virtual void onJob(uint8_t index, JobStep step, uint32_t atSec) = 0;
  virtual void onTest(TestStep step, bool ok, const std::string& error) = 0;
};

class Scheduler {
 public:
  Scheduler(Clock& clock, RamLog& log, CameraFactory factory, SchedulerListener* listener = nullptr)
      : clock_(clock), log_(log), factory_(std::move(factory)), listener_(listener) {}

  /// `previous` (from a reinitialize) lets attempts that already finished stay Complete.
  void initialize(const Config& config, const std::vector<Job>* previous = nullptr);
  void teardown();
  /// Re-read the clock: plan everything again.
  void reinitialize();

  Phase phase() const;
  /// True while a scheduled attempt or a test owns the camera.
  bool locked() const;
  NextEvent next() const;
  size_t attemptsRemaining() const;
  const std::vector<Job>& jobs() const { return jobs_; }
  Camera* camera() { return cam_.get(); }
  const Camera* camera() const { return cam_.get(); }
  const Config& config() const { return config_; }

  /// Run every step that is due. Each camera call blocks until it returns.
  void tick();
  /// When tick() next has work (Unix ms), or 0 if nothing is pending.
  uint64_t nextDueMs() const;

  /// Start the wake, start, stop, sleep test. It runs from tick(). Returns false if the camera is busy.
  bool runTest();

 private:
  struct Step {
    uint64_t atMs;
    uint8_t job;
    JobStep kind;  // Wake, Start or Stop
    bool done = false;
  };
  void run(Step& s);
  void stepTest();
  void emit(uint8_t index, JobStep step);

  Clock& clock_;
  RamLog& log_;
  CameraFactory factory_;
  SchedulerListener* listener_;
  Config config_;
  std::vector<Job> jobs_;
  std::vector<Step> steps_;
  std::unique_ptr<Camera> cam_;
  bool armed_ = false;

  bool testing_ = false;
  int testStage_ = 0;
  uint64_t testAtMs_ = 0;
};

}  // namespace kairos
