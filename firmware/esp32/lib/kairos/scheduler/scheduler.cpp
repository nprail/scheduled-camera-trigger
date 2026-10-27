#include "scheduler.h"

#include <algorithm>

namespace kairos {

void Scheduler::initialize(const Config& config, const std::vector<Job>* previous) {
  config_ = config;
  log_.log("INDEX", "INITIALIZE");

  cam_ = factory_ ? factory_(config_) : nullptr;
  if (!cam_) log_.log("INDEX", "Camera init failed");

  const uint64_t now = clock_.nowMs();
  armed_ = clock_.valid();
  jobs_.clear();
  steps_.clear();

  for (size_t i = 0; i < config_.attempts.size(); i++) {
    const Attempt& a = config_.attempts[i];
    Job job;
    job.name = a.name;
    job.launchMs = static_cast<uint64_t>(a.time) * 1000;
    // start recording x seconds before ignition, and wake the camera x seconds before that
    job.recordMs = job.launchMs - static_cast<uint64_t>(config_.startBefore) * 1000;
    job.wakeMs = job.recordMs - static_cast<uint64_t>(config_.wakeUpTimeout) * 1000;
    job.stopMs = job.launchMs + static_cast<uint64_t>(config_.endAfter) * 1000;
    const uint8_t index = static_cast<uint8_t>(i);

    const Job* before = nullptr;
    if (previous) {
      for (const Job& p : *previous) {
        if (p.launchMs == job.launchMs && p.name == job.name) before = &p;
      }
    }
    // an attempt that already ran stays Complete, and one already reported Missed is not reported again
    if (before && before->state == JobState::Complete) {
      job.state = JobState::Complete;
      jobs_.push_back(job);
      continue;
    }
    // anything whose wake time has already passed can't run in full (and nothing runs on an invalid clock)
    if (job.wakeMs <= now || !armed_) {
      job.state = JobState::Missed;
      jobs_.push_back(job);
      if (armed_ && !(before && before->state == JobState::Missed)) emit(index, JobStep::Missed);
      continue;
    }
    jobs_.push_back(job);
    steps_.push_back(Step{job.wakeMs, index, JobStep::Wake});
    steps_.push_back(Step{job.recordMs, index, JobStep::Start});
    steps_.push_back(Step{job.stopMs, index, JobStep::Stop});
  }
  std::stable_sort(steps_.begin(), steps_.end(), [](const Step& a, const Step& b) { return a.atMs < b.atMs; });
  log_.log("INDEX", std::to_string(config_.attempts.size()) + " attempts scheduled");
}

void Scheduler::teardown() {
  log_.log("INDEX", "TEARDOWN");
  steps_.clear();
  jobs_.clear();
  cam_.reset();
  testing_ = false;
}

void Scheduler::reinitialize() {
  Config c = config_;
  std::vector<Job> before = jobs_;
  teardown();
  initialize(c, &before);
}

Phase Scheduler::phase() const {
  if (testing_) return Phase::Test;
  for (const auto& j : jobs_) {
    if (j.state == JobState::Active) return j.recording ? Phase::Recording : Phase::Awake;
  }
  if (cam_ && cam_->recording()) return Phase::Recording;
  return Phase::Idle;
}

bool Scheduler::locked() const {
  if (testing_) return true;
  for (const auto& j : jobs_) {
    if (j.state == JobState::Active) return true;
  }
  return false;
}

NextEvent Scheduler::next() const {
  NextEvent best;
  const uint64_t now = clock_.nowMs();
  for (size_t i = 0; i < jobs_.size(); i++) {
    const Job& j = jobs_[i];
    if (j.state == JobState::Complete || j.state == JobState::Missed) continue;
    const struct { EventType type; uint64_t at; } events[3] = {
        {EventType::Wake, j.wakeMs}, {EventType::Start, j.recordMs}, {EventType::Stop, j.stopMs}};
    for (const auto& e : events) {
      if (e.at > now && (!best.valid || e.at < best.atMs)) {
        best.valid = true;
        best.type = e.type;
        best.atMs = e.at;
        best.index = static_cast<uint8_t>(i);
      }
    }
  }
  return best;
}

size_t Scheduler::attemptsRemaining() const {
  size_t n = 0;
  for (const auto& j : jobs_) n += (j.state == JobState::Pending || j.state == JobState::Active) ? 1 : 0;
  return n;
}

void Scheduler::emit(uint8_t index, JobStep step) {
  if (listener_) listener_->onJob(index, step, static_cast<uint32_t>(clock_.nowMs() / 1000));
}

void Scheduler::run(Step& s) {
  s.done = true;
  Job& job = jobs_[s.job];
  switch (s.kind) {
    case JobStep::Wake:
      log_.log("INDEX", "Start '" + job.name + "'");
      job.state = JobState::Active;
      emit(s.job, JobStep::Wake);
      if (cam_) cam_->wake();
      break;
    case JobStep::Start:
      if (cam_) cam_->start();
      job.recording = true;
      emit(s.job, JobStep::Start);
      break;
    case JobStep::Stop:
      if (cam_) cam_->stop();
      job.recording = false;
      emit(s.job, JobStep::Stop);
      log_.log("INDEX", "Complete '" + job.name + "'");
      if (cam_) cam_->sleep();
      job.state = JobState::Complete;
      emit(s.job, JobStep::Complete);
      break;
    default:
      break;
  }
}

void Scheduler::tick() {
  stepTest();
  if (!armed_) return;
  const uint64_t now = clock_.nowMs();
  // run() can change nothing about the step list, so index by position
  for (size_t i = 0; i < steps_.size(); i++) {
    if (!steps_[i].done && steps_[i].atMs <= now) run(steps_[i]);
  }
}

uint64_t Scheduler::nextDueMs() const {
  uint64_t best = 0;
  auto consider = [&](uint64_t at) {
    if (best == 0 || at < best) best = at;
  };
  if (armed_) {
    for (const auto& s : steps_) {
      if (!s.done) consider(s.atMs);
    }
  }
  if (testing_) consider(testAtMs_);
  return best;
}

bool Scheduler::runTest() {
  if (locked() || !cam_) return false;
  testing_ = true;
  testStage_ = 0;
  testAtMs_ = clock_.nowMs();
  return true;
}

void Scheduler::stepTest() {
  if (!testing_ || clock_.nowMs() < testAtMs_) return;
  auto say = [&](TestStep step, bool ok = false, const std::string& err = std::string()) {
    if (listener_) listener_->onTest(step, ok, err);
  };
  switch (testStage_) {
    case 0:
      say(TestStep::Wake);
      cam_->wake();
      testAtMs_ = clock_.nowMs() + 1000;
      testStage_ = 1;
      break;
    case 1:
      say(TestStep::Start);
      cam_->start();
      testAtMs_ = clock_.nowMs() + 2000;
      testStage_ = 2;
      break;
    default:
      say(TestStep::Stop);
      cam_->stop();
      say(TestStep::Sleep);
      cam_->sleep();
      say(TestStep::Done, true);
      testing_ = false;
      break;
  }
}

}  // namespace kairos
