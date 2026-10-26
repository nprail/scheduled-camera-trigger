// Host tests for the Kairos firmware logic. They run the shared vectors in docs/test-vectors.json
// (the same file the Node reference and both apps pass) and the device behaviour, with a fake clock,
// fake camera and in-memory storage. Run with: pio test -e native
#include <unity.h>

#include <cstdio>
#include <map>
#include <memory>

#include <kairos.h>

#include "mini_json.h"

using namespace kairos;

// ---------------------------------------------------------------------------
// helpers

static Json g_vectors;

static Bytes H(const std::string& hex) {
  Bytes b;
  TEST_ASSERT_TRUE_MESSAGE(fromHex(hex, b), hex.c_str());
  return b;
}


static Config configFromJson(const Json& j) {
  Config c;
  c.cameraType = j["cameraType"].s == "zcam" ? CameraType::ZCam : CameraType::Generic;
  c.wakeUpTimeout = static_cast<uint16_t>(j["wakeUpTimeout"].n);
  c.startBefore = static_cast<uint16_t>(j["startBefore"].n);
  c.endAfter = static_cast<uint16_t>(j["endAfter"].n);
  if (!j["cameraIp"].isNull()) {
    unsigned a, b, cc, d;
    std::sscanf(j["cameraIp"].s.c_str(), "%u.%u.%u.%u", &a, &b, &cc, &d);
    c.cameraIp[0] = static_cast<uint8_t>(a); c.cameraIp[1] = static_cast<uint8_t>(b);
    c.cameraIp[2] = static_cast<uint8_t>(cc); c.cameraIp[3] = static_cast<uint8_t>(d);
  }
  c.name = j["name"].s;
  for (const auto& a : j["attempts"].a) {
    Attempt at;
    at.time = static_cast<uint32_t>(a["time"].n);
    at.name = a["name"].s;
    c.attempts.push_back(at);
  }
  return c;
}

static const std::map<std::string, Op>& opMap() {
  static const std::map<std::string, Op> m = {
      {"hello", Op::Hello}, {"auth", Op::Auth}, {"auth.pair", Op::AuthPair}, {"time.get", Op::TimeGet},
      {"time.set", Op::TimeSet}, {"config.get", Op::ConfigGet}, {"config.set", Op::ConfigSet}, {"jobs.get", Op::JobsGet},
      {"camera.wake", Op::CameraWake}, {"camera.start", Op::CameraStart}, {"camera.stop", Op::CameraStop},
      {"camera.sleep", Op::CameraSleep}, {"test.run", Op::TestRun}, {"logs.get", Op::LogsGet}, {"logs.subscribe", Op::LogsSubscribe}};
  return m;
}

static LogLine lineFromJson(const Json& j) {
  LogLine l;
  l.seq = static_cast<uint32_t>(j["seq"].n);
  l.ts = static_cast<uint32_t>(j["ts"].n);
  l.ns = j["ns"].s;
  l.msg = j["msg"].s;
  return l;
}

static Err errFromName(const std::string& n) {
  static const std::map<std::string, Err> m = {
      {"bad_request", Err::BadRequest}, {"unauthorized", Err::Unauthorized}, {"locked_out", Err::LockedOut},
      {"not_supported", Err::NotSupported}, {"busy", Err::Busy}, {"invalid_config", Err::InvalidConfig},
      {"too_large", Err::TooLarge}, {"pairing_closed", Err::PairingClosed}, {"internal", Err::Internal}};
  return m.at(n);
}

// ---------------------------------------------------------------------------
// fakes and a whole device

struct FakeClock : Clock {
  uint64_t ms = 1793534400000ULL;  // 2026-11-01T12:00:00Z
  bool isValid = true;
  uint64_t nowMs() override { return ms; }
  void setNowMs(uint64_t v) override { ms = v; isValid = true; }
  bool valid() override { return isValid; }
};

struct FakeStorage : Storage {
  bool hasConfig = false;
  uint32_t rev = 0;
  Bytes blob;
  bool hasSecretStored = false;
  uint8_t secretStored[16] = {0};
  bool failSave = false;
  bool loadConfig(uint32_t& r, Bytes& b) override {
    if (!hasConfig) return false;
    r = rev;
    b = blob;
    return true;
  }
  bool saveConfig(uint32_t r, const Bytes& b) override {
    if (failSave) return false;
    hasConfig = true;
    rev = r;
    blob = b;
    return true;
  }
  bool loadSecret(uint8_t out[16]) override {
    if (!hasSecretStored) return false;
    std::memcpy(out, secretStored, 16);
    return true;
  }
  bool saveSecret(const uint8_t s[16]) override {
    hasSecretStored = true;
    std::memcpy(secretStored, s, 16);
    return true;
  }
};

struct FakeCamera : Camera {
  std::vector<std::string>* calls;
  bool rec = false;
  bool isReady = true;
  explicit FakeCamera(std::vector<std::string>* c) : calls(c) {}
  void wake() override { calls->push_back("wake"); }
  void start() override { calls->push_back("start"); rec = true; }
  void stop() override { calls->push_back("stop"); rec = false; }
  void sleep() override { calls->push_back("sleep"); }
  bool recording() const override { return rec; }
  bool ready() const override { return isReady; }
};

struct FakeRandom : Random {
  uint8_t next = 1;
  void fill(uint8_t* out, size_t n) override {
    for (size_t i = 0; i < n; i++) out[i] = next++;
  }
};

struct Recorder : SchedulerListener {
  std::vector<std::string> events;
  void onJob(uint8_t index, JobStep step, uint32_t at) override {
    events.push_back("job:" + std::to_string(index) + ":" + std::to_string(static_cast<int>(step)) + ":" + std::to_string(at));
  }
  void onTest(TestStep step, bool ok, const std::string& error) override {
    events.push_back("test:" + std::to_string(static_cast<int>(step)) + ":" + (ok ? "ok" : "no") + ":" + error);
  }
};

static const uint64_t kNow = 1793534400000ULL;
static const uint32_t kNowSec = 1793534400;
static const char* kSecretHex = "00112233445566778899aabbccddeeff";

struct Device {
  FakeClock clock;
  FakeStorage storage;
  std::vector<std::string> cameraCalls;
  RamLog log{clock};
  Recorder recorder;
  Scheduler scheduler{clock, log, [this](const Config&) { return std::unique_ptr<Camera>(new FakeCamera(&cameraCalls)); }, &recorder};
  Controller controller{clock, storage, log, scheduler};
  FakeRandom random;
  Dispatcher dispatcher{controller, random};

  void begin() { controller.begin(); }
  FakeCamera* cam() { return static_cast<FakeCamera*>(scheduler.camera()); }

  /// A valid config with one attempt an hour from now.
  Config config() {
    Config c;
    c.name = "Test";
    c.attempts.push_back(Attempt{kNowSec + 3600, "Attempt 1"});
    return c;
  }
  void pairWith(const char* hex = kSecretHex) {
    Bytes s = H(hex);
    controller.setSecret(s.data());
  }
};

// A client talking to a Link through the real framing.
struct Client {
  Device& dev;
  std::vector<Bytes> received;  // complete messages from the device
  Reassembler reasm;
  bool subscribed = true;
  size_t mtuPayload = 20;
  Link link;
  size_t notifications = 0;
  size_t maxNotification = 0;
  uint8_t nextId = 1;

  explicit Client(Device& d, size_t payload = 20)
      : dev(d),
        mtuPayload(payload),
        link(d.dispatcher,
             [this](const Bytes& f) {
               if (!subscribed) return false;
               notifications++;
               if (f.size() > maxNotification) maxNotification = f.size();
               FrameError e;
               if (reasm.push(f.data(), f.size(), e)) received.push_back(reasm.take());
               return true;
             },
             [payload]() { return payload; }) {}

  void send(const Bytes& message) {
    for (const Bytes& f : fragment(message, mtuPayload)) link.onWrite(f.data(), f.size(), dev.clock.nowMs());
  }
  /// Send a request and return the response message.
  Bytes request(const Bytes& message) {
    size_t before = received.size();
    send(message);
    link.flush();
    TEST_ASSERT_TRUE_MESSAGE(received.size() > before, "no response");
    return received.back();
  }
  Bytes op(uint8_t code, const Bytes& body = Bytes()) {
    Bytes m = {code, nextId++};
    m.insert(m.end(), body.begin(), body.end());
    return request(m);
  }
  /// hello + auth with a known secret.
  Bytes authenticate(const char* secretHex = kSecretHex) {
    Bytes hello = op(0x01);
    uint8_t nonce[16];
    std::memcpy(nonce, hello.data() + 5, 16);
    uint8_t cnonce[16];
    for (int i = 0; i < 16; i++) cnonce[i] = static_cast<uint8_t>(100 + i);
    Bytes key = H(secretHex);
    uint8_t proof[32];
    clientProof(key.data(), nonce, cnonce, proof);
    Bytes body(cnonce, cnonce + 16);
    body.insert(body.end(), proof, proof + 32);
    return op(0x02, body);
  }
};

static uint8_t errCode(const Bytes& r) { return r.size() >= 3 && r[0] == 0x80 ? r[2] : 0xff; }
static bool isOk(const Bytes& r) { return r.size() >= 3 && r[0] == 0x80 && r[2] == 0; }

static Bytes configBody(const Config& c) { return encodeConfig(c); }

// ---------------------------------------------------------------------------
// vectors

void test_sha256_and_hmac() {
  // RFC 4231 test case 2 key/data, fed through the three-part interface
  const char* key = "Jefe";
  const char* data = "what do ya want for nothing?";
  const uint8_t* parts[3] = {reinterpret_cast<const uint8_t*>(data), reinterpret_cast<const uint8_t*>(data) + 28, reinterpret_cast<const uint8_t*>(data) + 28};
  const size_t lens[3] = {28, 0, 0};
  uint8_t out[32];
  hmacSha256(reinterpret_cast<const uint8_t*>(key), 4, parts, lens, out);
  TEST_ASSERT_EQUAL_STRING("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", toHex(out, 32).c_str());

  Sha256 s;
  s.update(reinterpret_cast<const uint8_t*>("abc"), 3);
  s.final(out);
  TEST_ASSERT_EQUAL_STRING("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", toHex(out, 32).c_str());
}

void test_vectors_auth() {
  const Json& a = g_vectors["auth"];
  Bytes secret = H(a["secretHex"].s), nonce = H(a["nonceHex"].s), cn = H(a["clientNonceHex"].s);
  uint8_t out[32];
  clientProof(secret.data(), nonce.data(), cn.data(), out);
  TEST_ASSERT_EQUAL_STRING(a["clientProofHex"].s.c_str(), toHex(out, 32).c_str());
  serverProof(secret.data(), nonce.data(), cn.data(), out);
  TEST_ASSERT_EQUAL_STRING(a["serverProofHex"].s.c_str(), toHex(out, 32).c_str());
}

void test_vectors_framing() {
  for (const auto& v : g_vectors["framing"].a) {
    Bytes payload = H(v["payloadHex"].s);
    auto frags = fragment(payload, static_cast<size_t>(v["maxFragment"].n));
    TEST_ASSERT_EQUAL_UINT_MESSAGE(v["fragments"].size(), frags.size(), v["name"].s.c_str());
    for (size_t i = 0; i < frags.size(); i++) TEST_ASSERT_EQUAL_STRING_MESSAGE(v["fragments"][i].s.c_str(), toHex(frags[i]).c_str(), v["name"].s.c_str());

    Reassembler r;
    Bytes message;
    bool done = false;
    for (const auto& f : frags) {
      FrameError e;
      if (r.push(f.data(), f.size(), e)) { message = r.take(); done = true; }
      TEST_ASSERT_TRUE(e == FrameError::None);
    }
    TEST_ASSERT_TRUE_MESSAGE(done, v["name"].s.c_str());
    TEST_ASSERT_EQUAL_STRING_MESSAGE(v["payloadHex"].s.c_str(), toHex(message).c_str(), v["name"].s.c_str());
  }
}

static Phase phaseFrom(const std::string& s) {
  if (s == "idle") return Phase::Idle;
  if (s == "awake") return Phase::Awake;
  if (s == "recording") return Phase::Recording;
  if (s == "test") return Phase::Test;
  return Phase::Error;
}
static EventType typeFrom(const std::string& s) {
  if (s == "wake") return EventType::Wake;
  if (s == "start") return EventType::Start;
  if (s == "stop") return EventType::Stop;
  return EventType::None;
}

void test_vectors_status_and_device_info() {
  for (const auto& v : g_vectors["status"].a) {
    const Json& e = v["expected"];
    StatusValue s;
    s.flags = static_cast<uint8_t>(e["flags"].n);
    s.phase = phaseFrom(e["phase"].s);
    s.configRev = static_cast<uint32_t>(e["configRev"].n);
    s.nextEventAt = static_cast<uint32_t>(e["nextEventAt"].n);
    s.nextEventType = typeFrom(e["nextEventType"].s);
    s.attemptsRemaining = static_cast<uint8_t>(e["attemptsRemaining"].n);
    s.nextAttempt = e["nextAttempt"].isNull() ? kNoAttempt : static_cast<uint8_t>(e["nextAttempt"].n);
    Bytes b = encodeStatus(s);
    TEST_ASSERT_EQUAL_UINT(kStatusLen, b.size());
    TEST_ASSERT_EQUAL_STRING(v["hex"].s.c_str(), toHex(b).c_str());
  }
  for (const auto& v : g_vectors["deviceInfo"].a) {
    const Json& e = v["expected"];
    DeviceInfo d;
    d.v = static_cast<uint8_t>(e["v"].n);
    TEST_ASSERT_TRUE(parseDeviceId(e["id"].s, d.id));
    d.cam = e["cam"].s == "zcam" ? CameraType::ZCam : CameraType::Generic;
    d.cams = 0;
    for (const auto& c : e["cams"].a) d.cams |= c.s == "zcam" ? kCamZcam : kCamGeneric;
    d.fw = e["fw"].s;
    d.name = e["name"].s;
    TEST_ASSERT_EQUAL_STRING(v["hex"].s.c_str(), toHex(encodeDeviceInfo(d)).c_str());
    TEST_ASSERT_EQUAL_STRING(e["id"].s.c_str(), formatDeviceId(d.id).c_str());
  }
}

void test_vectors_config() {
  for (const auto& v : g_vectors["config"].a) {
    Config expected = configFromJson(v["config"]);
    Bytes bytes = H(v["hex"].s);
    TEST_ASSERT_EQUAL_STRING_MESSAGE(v["hex"].s.c_str(), toHex(encodeConfig(expected)).c_str(), v["name"].s.c_str());
    Config decoded;
    TEST_ASSERT_TRUE_MESSAGE(decodeConfig(bytes.data(), bytes.size(), decoded), v["name"].s.c_str());
    TEST_ASSERT_TRUE_MESSAGE(decoded == expected, v["name"].s.c_str());
  }
  // malformed configs are refused
  Config c;
  Bytes ok = H(g_vectors["config"][0]["hex"].s);
  Bytes wrongLayout = ok;
  wrongLayout[0] = 9;
  TEST_ASSERT_FALSE(decodeConfig(wrongLayout.data(), wrongLayout.size(), c));
  TEST_ASSERT_FALSE(decodeConfig(ok.data(), ok.size() - 1, c));
  Config big;
  Bytes tooMany = encodeConfig(big);
  tooMany.back() = 31;
  TEST_ASSERT_FALSE(decodeConfig(tooMany.data(), tooMany.size(), c));
}

static Bytes buildResponse(const std::string& opName, uint8_t id, const Json& f) {
  switch (opMap().at(opName)) {
    case Op::Hello: {
      HelloResp h;
      h.v = static_cast<uint8_t>(f["v"].n);
      h.paired = f["paired"].b;
      Bytes n = H(f["nonceHex"].s);
      std::memcpy(h.nonce, n.data(), 16);
      h.nowMs = f["nowMs"].u64();
      h.maxMessage = static_cast<uint16_t>(f["maxMessage"].n);
      return encodeHelloResponse(id, h);
    }
    case Op::Auth: {
      Bytes p = H(f["proofHex"].s);
      return encodeAuthResponse(id, p.data());
    }
    case Op::TimeGet: return encodeTimeGetResponse(id, f["nowMs"].u64());
    case Op::TimeSet: return encodeTimeSetResponse(id, f["nowMs"].u64(), static_cast<int32_t>(f["offsetMs"].i64()));
    case Op::ConfigGet: return encodeConfigGetResponse(id, configFromJson(f["config"]));
    case Op::ConfigSet: return encodeConfigSetResponse(id, static_cast<uint32_t>(f["configRev"].n));
    case Op::JobsGet: {
      std::vector<JobState> s;
      for (const auto& st : f["states"].a) {
        s.push_back(st.s == "active" ? JobState::Active : st.s == "complete" ? JobState::Complete : st.s == "missed" ? JobState::Missed : JobState::Pending);
      }
      return encodeJobsResponse(id, s);
    }
    case Op::CameraWake: case Op::CameraStart: case Op::CameraStop: case Op::CameraSleep: return encodeCameraResponse(id, f["recording"].b);
    case Op::LogsGet: {
      std::vector<LogLine> lines;
      for (const auto& l : f["lines"].a) lines.push_back(lineFromJson(l));
      return encodeLogsGetResponse(id, static_cast<uint32_t>(f["last"].n), f["more"].b, lines);
    }
    default: return encodeEmptyResponse(id);  // auth.pair, test.run, logs.subscribe
  }
}

static Bytes buildEvent(const std::string& ev, const Json& f) {
  if (ev == "job") {
    const std::string& s = f["step"].s;
    JobStep step = s == "wake" ? JobStep::Wake : s == "start" ? JobStep::Start : s == "stop" ? JobStep::Stop : s == "complete" ? JobStep::Complete : JobStep::Missed;
    return encodeJobEvent(static_cast<uint8_t>(f["attempt"].n), step, static_cast<uint32_t>(f["at"].n));
  }
  if (ev == "config") return encodeConfigEvent(static_cast<uint32_t>(f["configRev"].n));
  if (ev == "test") {
    const std::string& s = f["step"].s;
    TestStep step = s == "wake" ? TestStep::Wake : s == "start" ? TestStep::Start : s == "stop" ? TestStep::Stop : s == "sleep" ? TestStep::Sleep : TestStep::Done;
    return encodeTestEvent(step, f["ok"].b, f["error"].s);
  }
  return encodeLogEvent(lineFromJson(f));
}

void test_vectors_messages() {
  for (const auto& v : g_vectors["messages"].a) {
    const std::string& name = v["name"].s;
    const std::string& kind = v["kind"].s;
    const Json& f = v["fields"];
    if (kind == "request") {
      Request q;
      uint8_t id = 0;
      Bytes bytes = H(v["hex"].s);
      TEST_ASSERT_TRUE_MESSAGE(decodeRequest(bytes, q, id), name.c_str());
      TEST_ASSERT_TRUE_MESSAGE(q.op == opMap().at(v["op"].s), name.c_str());
      TEST_ASSERT_EQUAL_UINT8_MESSAGE(static_cast<uint8_t>(v["id"].n), q.id, name.c_str());
      switch (q.op) {
        case Op::Auth:
          TEST_ASSERT_EQUAL_STRING(f["clientNonceHex"].s.c_str(), toHex(q.clientNonce, 16).c_str());
          TEST_ASSERT_EQUAL_STRING(f["proofHex"].s.c_str(), toHex(q.proof, 32).c_str());
          break;
        case Op::AuthPair: TEST_ASSERT_EQUAL_STRING(f["secretHex"].s.c_str(), toHex(q.secret, 16).c_str()); break;
        case Op::TimeSet: TEST_ASSERT_TRUE(q.nowMs == f["nowMs"].u64()); break;
        case Op::ConfigSet: TEST_ASSERT_TRUE_MESSAGE(q.config == configFromJson(f["config"]), name.c_str()); break;
        case Op::LogsGet:
          TEST_ASSERT_EQUAL_UINT32(static_cast<uint32_t>(f["after"].n), q.after);
          TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(f["limit"].n), q.limit);
          break;
        case Op::LogsSubscribe:
          TEST_ASSERT_EQUAL(f["enabled"].b, q.enabled);
          TEST_ASSERT_EQUAL_UINT32(static_cast<uint32_t>(f["after"].n), q.after);
          break;
        default: break;
      }
    } else if (kind == "response") {
      Bytes built = buildResponse(v["op"].s, static_cast<uint8_t>(v["id"].n), f);
      TEST_ASSERT_EQUAL_STRING_MESSAGE(v["hex"].s.c_str(), toHex(built).c_str(), name.c_str());
    } else {
      TEST_ASSERT_EQUAL_STRING_MESSAGE(v["hex"].s.c_str(), toHex(buildEvent(v["ev"].s, f)).c_str(), name.c_str());
    }
  }
}

void test_vectors_errors() {
  for (const auto& v : g_vectors["errors"].a) {
    ErrorDetail d;
    const Json& det = v["detail"];
    if (det.has("retryAfter")) d.retryAfter = static_cast<uint16_t>(det["retryAfter"].n);
    if (det.has("field")) {
      d.field = static_cast<Field>(static_cast<int>(det["field"].n));
      d.index = static_cast<uint8_t>(det["index"].n);
      d.reason = static_cast<Reason>(static_cast<int>(det["reason"].n));
    }
    if (det.has("message")) d.message = det["message"].s;
    Bytes b = encodeError(static_cast<uint8_t>(v["id"].n), errFromName(v["code"].s), d);
    TEST_ASSERT_EQUAL_STRING_MESSAGE(v["hex"].s.c_str(), toHex(b).c_str(), v["name"].s.c_str());
  }
}

void test_a_short_or_malformed_request_is_refused() {
  Request q;
  uint8_t id = 99;
  TEST_ASSERT_FALSE(decodeRequest(Bytes{0x05}, q, id));
  TEST_ASSERT_EQUAL_UINT8(0, id);
  TEST_ASSERT_FALSE(decodeRequest(Bytes{0x05, 7, 1, 2}, q, id));  // time.set needs 8 bytes
  TEST_ASSERT_EQUAL_UINT8(7, id);
  TEST_ASSERT_TRUE(decodeRequest(Bytes{0x05, 7, 0, 0, 0, 0, 0, 0, 0, 0, 9, 9}, q, id));  // trailing bytes are ignored
  TEST_ASSERT_TRUE(decodeRequest(Bytes{0x7f, 3}, q, id));
  TEST_ASSERT_TRUE(q.op == Op::Unknown);
}

// ---------------------------------------------------------------------------
// protocol behaviour

void test_hello_reports_pairing_and_a_fresh_nonce() {
  Device d;
  d.begin();
  Client c(d);
  Bytes a = c.op(0x01);
  Bytes b = c.op(0x01);
  TEST_ASSERT_TRUE(isOk(a));
  TEST_ASSERT_EQUAL_UINT8(2, a[3]);  // v
  TEST_ASSERT_EQUAL_UINT8(0, a[4]);  // not paired
  TEST_ASSERT_TRUE(a != b);
  d.pairWith();
  TEST_ASSERT_EQUAL_UINT8(1, c.op(0x01)[4]);
}

void test_everything_but_the_open_ops_needs_auth() {
  Device d;
  d.begin();
  Client c(d);
  Bytes timeBody = Writer().u64(1).take();
  Bytes logsBody = Writer().u32(0).u8(1).take();
  const std::pair<uint8_t, Bytes> ops[] = {{0x07, {}}, {0x08, configBody(d.config())}, {0x09, {}}, {0x11, {}}, {0x14, {}}, {0x20, logsBody}, {0x05, timeBody}};
  for (const auto& o : ops) TEST_ASSERT_EQUAL_UINT8_MESSAGE(static_cast<uint8_t>(Err::Unauthorized), errCode(c.op(o.first, o.second)), "needs auth");
  Bytes t = c.op(0x04);
  TEST_ASSERT_TRUE(isOk(t));
  TEST_ASSERT_TRUE(Reader(t.data() + 3, 8).u64() == kNow);
}

void test_unknown_ops_are_not_supported() {
  Device d;
  d.begin();
  d.pairWith();
  Client c(d);
  TEST_ASSERT_TRUE(isOk(c.authenticate()));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::NotSupported), errCode(c.op(0x7f)));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::NotSupported), errCode(c.op(0x06)));  // v1's status.get
}

void test_pairing_needs_the_window_then_stores_the_secret() {
  Device d;
  d.begin();
  Client c(d);
  Bytes secret = H(kSecretHex);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::PairingClosed), errCode(c.op(0x03, secret)));

  d.controller.openPairing();
  TEST_ASSERT_TRUE(d.controller.pairingOpen());
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.request(Bytes{0x03, 40, 1, 2, 3})));
  TEST_ASSERT_TRUE(isOk(c.op(0x03, secret)));
  TEST_ASSERT_FALSE(d.controller.pairingOpen());  // single use
  TEST_ASSERT_TRUE(d.storage.hasSecretStored);
  TEST_ASSERT_TRUE(isOk(c.op(0x07)));  // the session is authenticated

  Client c2(d);
  TEST_ASSERT_TRUE(isOk(c2.authenticate()));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::PairingClosed), errCode(c2.op(0x03, secret)));
}

void test_the_pairing_window_closes_on_its_own() {
  Device d;
  d.begin();
  d.controller.openPairing();
  d.clock.ms += 119000;
  TEST_ASSERT_TRUE(d.controller.pairingOpen());
  d.clock.ms += 2000;
  TEST_ASSERT_FALSE(d.controller.pairingOpen());
}

void test_auth_verifies_both_directions() {
  Device d;
  d.begin();
  d.pairWith();
  Client c(d);
  Bytes r = c.authenticate();
  TEST_ASSERT_TRUE(isOk(r));
  // recompute the server proof from the same nonces: the first hello gave nonce 1..16, the client used 100..115
  uint8_t nonce[16], cn[16], proof[32];
  for (int i = 0; i < 16; i++) { nonce[i] = static_cast<uint8_t>(1 + i); cn[i] = static_cast<uint8_t>(100 + i); }
  Bytes key = H(kSecretHex);
  serverProof(key.data(), nonce, cn, proof);
  TEST_ASSERT_EQUAL_STRING(toHex(proof, 32).c_str(), toHex(r.data() + 3, 32).c_str());

  Client bad(d);
  bad.op(0x01);
  Bytes body(48, 7);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Unauthorized), errCode(bad.op(0x02, body)));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Unauthorized), errCode(bad.op(0x07)));
}

void test_auth_before_hello_or_when_unpaired_fails() {
  Device d;
  d.begin();
  Client c(d);
  Bytes body(48, 7);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.op(0x02, body)));
  c.op(0x01);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Unauthorized), errCode(c.op(0x02, body)));
}

void test_five_failures_lock_auth_out() {
  Device d;
  d.begin();
  d.pairWith();
  Client c(d);
  c.op(0x01);
  Bytes body(48, 7);
  for (int i = 0; i < 5; i++) TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Unauthorized), errCode(c.op(0x02, body)));
  Bytes locked = c.op(0x02, body);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::LockedOut), errCode(locked));
  TEST_ASSERT_EQUAL_UINT16(30, Reader(locked.data() + 3, 2).u16());
  // even a correct proof is refused while locked
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::LockedOut), errCode(c.authenticate()));
  d.clock.ms += 31000;
  TEST_ASSERT_TRUE(isOk(c.authenticate()));
}

void test_the_lockout_doubles_up_to_five_minutes() {
  Lockout l;
  uint64_t t = 1000;
  uint32_t expected[] = {30, 60, 120, 240, 300, 300};
  for (uint32_t e : expected) {
    for (int i = 0; i < 5; i++) l.fail(t);
    TEST_ASSERT_EQUAL_UINT32(e, l.retryAfter(t));
    t += static_cast<uint64_t>(e) * 1000 + 1;
  }
  l.success();
  TEST_ASSERT_EQUAL_UINT32(0, l.retryAfter(t));
}

static Client& authed(Device& d, std::unique_ptr<Client>& holder, size_t payload = 20) {
  d.pairWith();
  holder.reset(new Client(d, payload));
  TEST_ASSERT_TRUE(isOk(holder->authenticate()));
  return *holder;
}

void test_status_carries_phase_next_event_and_attempt() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());
  StatusValue s = d.controller.status();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Idle), static_cast<uint8_t>(s.phase));
  TEST_ASSERT_TRUE(s.flags & flag::CameraReady);
  TEST_ASSERT_TRUE(s.flags & flag::ClockValid);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(EventType::Wake), static_cast<uint8_t>(s.nextEventType));
  TEST_ASSERT_EQUAL_UINT32(kNowSec + 3600 - 25, s.nextEventAt);
  TEST_ASSERT_EQUAL_UINT8(1, s.attemptsRemaining);
  TEST_ASSERT_EQUAL_UINT8(0, s.nextAttempt);
  TEST_ASSERT_EQUAL_UINT(13, encodeStatus(s).size());
}

void test_config_set_replaces_saves_and_reschedules() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  Bytes got = c.op(0x07);
  Config cfg;
  TEST_ASSERT_TRUE(decodeConfig(got.data() + 3, got.size() - 3, cfg));
  TEST_ASSERT_TRUE(cfg == d.controller.currentConfig());

  Config next = d.config();
  next.startBefore = 10;
  next.attempts.push_back(Attempt{kNowSec + 7200, "B"});
  Bytes set = c.op(0x08, configBody(next));
  TEST_ASSERT_TRUE(isOk(set));
  TEST_ASSERT_EQUAL_UINT32(2, Reader(set.data() + 3, 4).u32());
  TEST_ASSERT_EQUAL_UINT(2, d.scheduler.jobs().size());
  TEST_ASSERT_TRUE(d.controller.currentConfig() == next);
  TEST_ASSERT_TRUE(d.storage.hasConfig);
  TEST_ASSERT_EQUAL_UINT32(2, d.storage.rev);

  Bytes jobs = c.op(0x09);
  TEST_ASSERT_EQUAL_UINT8(2, jobs[3]);
  TEST_ASSERT_EQUAL_UINT8(0, jobs[4]);
}

static ErrorDetail detailOf(const Bytes& r) {
  ErrorDetail d;
  if (errCode(r) == static_cast<uint8_t>(Err::InvalidConfig)) {
    d.field = static_cast<Field>(r[3]);
    d.index = r[4];
    d.reason = static_cast<Reason>(r[5]);
  }
  return d;
}
#define ASSERT_INVALID(resp, f, i, r)                                                                  \
  do {                                                                                                 \
    Bytes resp_ = (resp);                                                                              \
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::InvalidConfig), errCode(resp_));                  \
    ErrorDetail dd_ = detailOf(resp_);                                                                 \
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(f), static_cast<uint8_t>(dd_.field));                  \
    TEST_ASSERT_EQUAL_UINT8(i, dd_.index);                                                             \
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(r), static_cast<uint8_t>(dd_.reason));                 \
  } while (0)

void test_config_set_validation_names_the_field_and_changes_nothing() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  Config base = d.config();
  d.controller.saveConfig(base);
  Config before = d.controller.currentConfig();

  Config past = base;
  past.attempts = {Attempt{kNowSec - 1, "x"}};
  ASSERT_INVALID(c.op(0x08, configBody(past)), Field::AttemptTime, 0, Reason::Past);

  Config soon = base;
  soon.attempts = {Attempt{kNowSec + 10, "x"}};  // the wake-up time (25 s before) has passed
  ASSERT_INVALID(c.op(0x08, configBody(soon)), Field::AttemptTime, 0, Reason::TooSoon);

  Config dup = base;
  dup.attempts = {Attempt{kNowSec + 7200, "a"}, Attempt{kNowSec + 7200, "b"}};
  ASSERT_INVALID(c.op(0x08, configBody(dup)), Field::AttemptTime, 1, Reason::Duplicate);

  Config noIp = base;
  noIp.cameraType = CameraType::ZCam;
  ASSERT_INVALID(c.op(0x08, configBody(noIp)), Field::CameraIp, 0, Reason::Required);

  Config range = base;
  range.endAfter = 5000;
  ASSERT_INVALID(c.op(0x08, configBody(range)), Field::EndAfter, 0, Reason::Range);

  Config emptyName = base;
  emptyName.attempts = {Attempt{kNowSec + 7200, ""}};
  ASSERT_INVALID(c.op(0x08, configBody(emptyName)), Field::AttemptName, 0, Reason::Range);

  TEST_ASSERT_TRUE(d.controller.currentConfig() == before);
  TEST_ASSERT_EQUAL_UINT32(2, d.controller.configRev());
}

void test_a_config_with_too_many_attempts_or_a_wrong_layout_is_a_bad_request() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  Config cfg = d.config();
  cfg.attempts.clear();
  Bytes body = configBody(cfg);
  body.back() = 31;
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.op(0x08, body)));
  Bytes layout = configBody(cfg);
  layout[0] = 9;
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.op(0x08, layout)));
}

void test_a_full_config_fragments_across_a_23_byte_mtu() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h, 20);
  Config cfg = d.config();
  cfg.attempts.clear();
  for (uint32_t i = 0; i < 30; i++) {
    std::string name = "Attempt " + std::to_string(i + 1);
    name.resize(32, '.');
    cfg.attempts.push_back(Attempt{kNowSec + 3600 * (i + 1), name});
  }
  size_t before = c.notifications;
  Bytes r = c.op(0x08, configBody(cfg));
  TEST_ASSERT_TRUE(isOk(r));
  Bytes got = c.op(0x07);
  Config back;
  TEST_ASSERT_TRUE(decodeConfig(got.data() + 3, got.size() - 3, back));
  TEST_ASSERT_TRUE(back == cfg);
  TEST_ASSERT_TRUE(c.notifications - before > 30);
  TEST_ASSERT_TRUE(c.maxNotification <= 20);
  TEST_ASSERT_EQUAL_UINT(30, d.scheduler.jobs().size());
}

void test_time_set_applies_the_clock_reschedules_and_reports_drift() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  uint64_t target = d.clock.ms - 418;
  Bytes r = c.op(0x05, Writer().u64(target).take());
  TEST_ASSERT_TRUE(isOk(r));
  Reader rd(r.data() + 3, 12);
  TEST_ASSERT_TRUE(rd.u64() == target);
  TEST_ASSERT_EQUAL_INT32(418, rd.i32());
  TEST_ASSERT_TRUE(d.clock.ms == target);
}

void test_manual_camera_control_is_idempotent_and_refused_while_busy() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  TEST_ASSERT_TRUE(isOk(c.op(0x10)));
  Bytes start = c.op(0x11);
  TEST_ASSERT_EQUAL_UINT8(1, start[3]);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Recording), static_cast<uint8_t>(d.controller.status().phase));
  c.op(0x11);  // already recording: the camera ignores it
  TEST_ASSERT_EQUAL_UINT8(0, c.op(0x12)[3]);
  TEST_ASSERT_TRUE(isOk(c.op(0x13)));
  std::vector<std::string> want = {"wake", "start", "start", "stop", "sleep"};
  TEST_ASSERT_TRUE(d.cameraCalls == want);
}

void test_a_scheduled_attempt_walks_its_phases_and_blocks_changes() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());  // one attempt at +3600 s: wake at -25, record at -20, stop at +30
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  d.cameraCalls.clear();
  d.recorder.events.clear();

  d.clock.ms = (static_cast<uint64_t>(kNowSec) + 3600 - 25) * 1000;
  d.scheduler.tick();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Awake), static_cast<uint8_t>(d.controller.status().phase));
  TEST_ASSERT_EQUAL_STRING(("job:0:0:" + std::to_string(kNowSec + 3600 - 25)).c_str(), d.recorder.events.back().c_str());

  // manual control, config, time and tests are refused mid-attempt
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Busy), errCode(c.op(0x12)));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Busy), errCode(c.op(0x08, configBody(d.config()))));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Busy), errCode(c.op(0x05, Writer().u64(kNow).take())));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::Busy), errCode(c.op(0x14)));

  d.clock.ms = (static_cast<uint64_t>(kNowSec) + 3600 - 20) * 1000;
  d.scheduler.tick();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Recording), static_cast<uint8_t>(d.controller.status().phase));
  TEST_ASSERT_EQUAL_UINT8(1, c.op(0x09)[4]);  // active

  d.clock.ms = (static_cast<uint64_t>(kNowSec) + 3600 + 30) * 1000;
  d.scheduler.tick();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Idle), static_cast<uint8_t>(d.controller.status().phase));
  TEST_ASSERT_EQUAL_UINT8(2, c.op(0x09)[4]);  // complete
  std::vector<std::string> want = {"wake", "start", "stop", "sleep"};
  TEST_ASSERT_TRUE(d.cameraCalls == want);
  StatusValue s = d.controller.status();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(EventType::None), static_cast<uint8_t>(s.nextEventType));
  TEST_ASSERT_EQUAL_UINT8(kNoAttempt, s.nextAttempt);
}

void test_attempts_whose_wake_time_has_passed_are_missed() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());
  d.clock.ms += (3600 - 10) * 1000ULL;  // 10 s before launch, wake was 25 s before
  d.scheduler.reinitialize();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(JobState::Missed), static_cast<uint8_t>(d.scheduler.jobs()[0].state));
  TEST_ASSERT_EQUAL_UINT(0, d.scheduler.attemptsRemaining());
  TEST_ASSERT_FALSE(d.scheduler.next().valid);
}

void test_nothing_is_armed_until_the_clock_is_valid() {
  Device d;
  d.clock.isValid = false;
  d.begin();
  d.controller.saveConfig(d.config());
  d.clock.ms = (static_cast<uint64_t>(kNowSec) + 3600 - 25) * 1000;
  d.scheduler.tick();
  TEST_ASSERT_TRUE(d.cameraCalls.empty());
  TEST_ASSERT_FALSE(d.controller.status().flags & flag::ClockValid);
  // a phone sets the clock: the schedule is planned against it
  int32_t offset;
  TEST_ASSERT_TRUE(d.controller.setTime(kNow, offset));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(JobState::Pending), static_cast<uint8_t>(d.scheduler.jobs()[0].state));
  TEST_ASSERT_TRUE(d.controller.status().flags & flag::ClockValid);
}

void test_a_completed_attempt_stays_complete_when_the_schedule_is_replanned() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());
  d.clock.ms = (static_cast<uint64_t>(kNowSec) + 3600 - 25) * 1000;
  d.scheduler.tick();
  d.clock.ms = (static_cast<uint64_t>(kNowSec) + 3600 + 30) * 1000;
  d.scheduler.tick();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(JobState::Complete), static_cast<uint8_t>(d.scheduler.jobs()[0].state));
  size_t events = d.recorder.events.size();
  d.scheduler.reinitialize();  // a clock sync or an RTC step
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(JobState::Complete), static_cast<uint8_t>(d.scheduler.jobs()[0].state));
  TEST_ASSERT_EQUAL_UINT(events, d.recorder.events.size());  // and nothing is announced again
}

void test_a_missed_attempt_is_announced_once() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());
  d.clock.ms += (3600 - 10) * 1000ULL;
  d.scheduler.reinitialize();
  size_t events = d.recorder.events.size();
  d.scheduler.reinitialize();
  TEST_ASSERT_EQUAL_UINT(events, d.recorder.events.size());
}

void test_run_test_streams_progress_and_finishes_idle() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  d.recorder.events.clear();
  TEST_ASSERT_TRUE(isOk(c.op(0x14)));
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Test), static_cast<uint8_t>(d.controller.status().phase));
  for (int i = 0; i < 8; i++) {
    d.scheduler.tick();
    d.clock.ms += 1000;
  }
  std::vector<std::string> want = {"test:0:no:", "test:1:no:", "test:2:no:", "test:3:no:", "test:4:ok:"};
  TEST_ASSERT_TRUE(d.recorder.events == want);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Idle), static_cast<uint8_t>(d.controller.status().phase));
  std::vector<std::string> calls = {"wake", "start", "stop", "sleep"};
  TEST_ASSERT_TRUE(d.cameraCalls == calls);
}

void test_logs_get_pages_by_seq_and_subscribe_replays() {
  Device d;
  d.begin();
  for (int i = 0; i < 30; i++) d.log.log("test", "line " + std::to_string(i));
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  Bytes first = c.op(0x20, Writer().u32(0).u8(10).take());
  TEST_ASSERT_TRUE(isOk(first));
  Reader r(first.data() + 3, first.size() - 3);
  uint32_t last = r.u32();
  TEST_ASSERT_EQUAL_UINT8(1, r.u8());  // more
  TEST_ASSERT_EQUAL_UINT8(10, r.u8());
  TEST_ASSERT_TRUE(last >= 10);

  Bytes rest = c.op(0x20, Writer().u32(last).u8(100).take());
  Reader r2(rest.data() + 3, rest.size() - 3);
  TEST_ASSERT_EQUAL_UINT32(d.log.seq(), r2.u32());
  TEST_ASSERT_EQUAL_UINT8(0, r2.u8());

  uint32_t seq = d.log.seq();
  d.log.log("test", "missed while away");
  size_t before = c.received.size();
  c.op(0x21, Writer().u8(1).u32(seq).take());
  TEST_ASSERT_TRUE(c.received.size() >= before + 2);  // the reply, then the replayed line as an event
  TEST_ASSERT_TRUE(isOk(c.received[before]));
  Bytes ev = c.received.back();
  TEST_ASSERT_EQUAL_UINT8(0x81, ev[0]);
  TEST_ASSERT_EQUAL_UINT8(0x04, ev[1]);
  TEST_ASSERT_TRUE(c.link.session().logSub);
}

void test_long_log_lines_are_cut() {
  Device d;
  d.begin();
  d.log.log("a-very-long-namespace", std::string(300, 'x'));
  std::vector<LogLine> l = d.log.since(d.log.seq() - 1, 1);
  TEST_ASSERT_EQUAL_UINT(8, l[0].ns.size());
  TEST_ASSERT_EQUAL_UINT(120, l[0].msg.size());
}

void test_the_log_keeps_the_last_500_lines() {
  Device d;
  for (int i = 0; i < 600; i++) d.log.log("t", "m");
  std::vector<LogLine> all = d.log.since(0, 1000);
  TEST_ASSERT_EQUAL_UINT(500, all.size());
  TEST_ASSERT_EQUAL_UINT32(101, all.front().seq);
}

void test_events_only_reach_authenticated_links() {
  Device d;
  d.begin();
  d.pairWith();
  Client anon(d), authedClient(d);
  authedClient.authenticate();
  Bytes ev = encodeConfigEvent(7);
  anon.link.pushEvent(ev);
  anon.link.flush();
  authedClient.link.pushEvent(ev);
  authedClient.link.flush();
  TEST_ASSERT_EQUAL_UINT(0, anon.received.size());
  TEST_ASSERT_EQUAL_UINT8(0x81, authedClient.received.back()[0]);
}

void test_malformed_input_gets_an_error_not_a_crash() {
  Device d;
  d.begin();
  std::unique_ptr<Client> h;
  Client& c = authed(d, h);
  Bytes tooShort = {0x80, 0x00, 0x01, 0x01};
  c.link.onWrite(tooShort.data(), tooShort.size(), d.clock.ms);
  c.link.flush();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.received.back()));
  TEST_ASSERT_EQUAL_UINT8(0, c.received.back()[1]);  // id 0: it could not be read

  Bytes orphan = {0x05, 1, 2};
  c.link.onWrite(orphan.data(), orphan.size(), d.clock.ms);
  c.link.flush();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.received.back()));

  Bytes huge = {0x80, 0xff, 0xff, 1, 2, 3};
  c.link.onWrite(huge.data(), huge.size(), d.clock.ms);
  c.link.flush();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::TooLarge), errCode(c.received.back()));
  TEST_ASSERT_TRUE(isOk(c.op(0x07)));  // the link still works
}

void test_a_stalled_message_times_out() {
  Device d;
  d.begin();
  Client c(d);
  Bytes start = {0x80, 0x00, 0x40, 1, 2, 3};
  c.link.onWrite(start.data(), start.size(), d.clock.ms);
  c.link.tick(d.clock.ms + 1000);
  TEST_ASSERT_EQUAL_UINT(0, c.received.size());
  c.link.tick(d.clock.ms + 6000);
  TEST_ASSERT_EQUAL_UINT(1, c.received.size());
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Err::BadRequest), errCode(c.received.back()));
}

void test_messages_for_an_unsubscribed_client_are_dropped() {
  Device d;
  d.begin();
  Client c(d);
  c.subscribed = false;
  c.send(Bytes{0x01, 1});
  TEST_ASSERT_EQUAL_UINT(0, c.link.flush());
  TEST_ASSERT_EQUAL_UINT(0, c.link.queued());
}

void test_an_oversized_response_becomes_too_large() {
  Device d;
  d.begin();
  Config big = d.config();
  big.attempts.clear();
  for (uint32_t i = 0; i < 30; i++) big.attempts.push_back(Attempt{kNowSec + 3600 * (i + 1), std::string(32, 'n')});
  d.controller.saveConfig(big);
  std::unique_ptr<Client> h;
  Client& c = authed(d, h, 244);
  Bytes got = c.op(0x07);
  TEST_ASSERT_TRUE(isOk(got));  // the largest config fits in a message
  TEST_ASSERT_TRUE(got.size() < kMaxMessage);
}

// ---------------------------------------------------------------------------
// controller

void test_defaults_when_nothing_is_stored() {
  Device d;
  d.begin();
  const Config& c = d.controller.currentConfig();
  TEST_ASSERT_TRUE(c.cameraType == CameraType::Generic);
  TEST_ASSERT_EQUAL_UINT16(5, c.wakeUpTimeout);
  TEST_ASSERT_EQUAL_UINT16(20, c.startBefore);
  TEST_ASSERT_EQUAL_UINT16(30, c.endAfter);
  TEST_ASSERT_EQUAL_UINT(0, c.attempts.size());
  TEST_ASSERT_EQUAL_UINT32(1, d.controller.configRev());
  TEST_ASSERT_FALSE(d.controller.paired());
}

void test_the_config_and_secret_survive_a_restart() {
  Device first;
  first.begin();
  first.pairWith();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(SaveStatus::Ok), static_cast<uint8_t>(first.controller.saveConfig(first.config()).status));

  // a new boot with the same flash
  Device second;
  second.storage = first.storage;
  second.begin();
  TEST_ASSERT_TRUE(second.controller.currentConfig() == first.config());
  TEST_ASSERT_EQUAL_UINT32(2, second.controller.configRev());
  TEST_ASSERT_TRUE(second.controller.paired());
  TEST_ASSERT_EQUAL_UINT(1, second.scheduler.jobs().size());
}

void test_a_corrupt_stored_config_falls_back_to_the_defaults_and_keeps_the_secret() {
  Device d;
  d.storage.hasConfig = true;
  d.storage.rev = 9;
  d.storage.blob = {0, 0, 0, 0, 9, 9, 9};
  d.storage.hasSecretStored = true;
  d.begin();
  TEST_ASSERT_EQUAL_UINT(0, d.controller.currentConfig().attempts.size());
  TEST_ASSERT_TRUE(d.controller.paired());
}

void test_a_failed_save_keeps_the_old_config_and_schedule() {
  Device d;
  d.begin();
  d.controller.saveConfig(d.config());
  d.storage.failSave = true;
  Config next = d.config();
  next.name = "Changed";
  SaveResult r = d.controller.saveConfig(next);
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(SaveStatus::Failed), static_cast<uint8_t>(r.status));
  TEST_ASSERT_EQUAL_STRING("Test", d.controller.currentConfig().name.c_str());
  TEST_ASSERT_EQUAL_UINT32(2, d.controller.configRev());
  TEST_ASSERT_EQUAL_UINT(1, d.scheduler.jobs().size());
}

void test_a_config_change_is_announced() {
  Device d;
  d.begin();
  uint32_t seen = 0;
  d.controller.onConfigChanged([&](uint32_t rev) { seen = rev; });
  d.controller.saveConfig(d.config());
  TEST_ASSERT_EQUAL_UINT32(2, seen);
}

void test_a_camera_that_is_not_ready_is_an_error_phase() {
  Device d;
  d.begin();
  d.cam()->isReady = false;
  StatusValue s = d.controller.status();
  TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(Phase::Error), static_cast<uint8_t>(s.phase));
  TEST_ASSERT_FALSE(s.flags & flag::CameraReady);
}

// ---------------------------------------------------------------------------
// config helpers and cameras

void test_names_are_limited_in_bytes() {
  Config c;
  c.name = std::string(33, 'a');
  auto e = validateConfig(c, kNow);
  TEST_ASSERT_TRUE(e.has_value());
  TEST_ASSERT_TRUE(e->field == Field::Name);
  c.name = std::string(32, 'a');
  TEST_ASSERT_FALSE(validateConfig(c, kNow).has_value());
}

void test_advertised_names_keep_the_id() {
  TEST_ASSERT_EQUAL_STRING("A1B2C3", advertisedName("", "A1B2C3").c_str());
  TEST_ASSERT_EQUAL_STRING("Pad Cam-A1B2C3", advertisedName("Pad Cam", "A1B2C3").c_str());
  std::string longName = advertisedName(std::string(32, 'x'), "A1B2C3");
  TEST_ASSERT_EQUAL_UINT(29, longName.size());
  TEST_ASSERT_TRUE(longName.substr(22) == "-A1B2C3");
  std::string utf8 = advertisedName("\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9\xC3\xA9", "A1B2C3");  // 12 x 2 bytes
  TEST_ASSERT_TRUE(utf8.size() <= 29);
  TEST_ASSERT_TRUE(utf8.substr(utf8.size() - 7) == "-A1B2C3");
}

void test_device_ids() {
  uint8_t id[3];
  TEST_ASSERT_TRUE(parseDeviceId("A1b2C3", id));
  TEST_ASSERT_EQUAL_STRING("A1B2C3", formatDeviceId(id).c_str());
  TEST_ASSERT_FALSE(parseDeviceId("A1B2", id));
  TEST_ASSERT_FALSE(parseDeviceId("ZZZZZZ", id));
}

struct FakeGpio : Gpio {
  std::vector<std::string> log;
  void output(uint8_t pin) override { log.push_back("out" + std::to_string(pin)); }
  void write(uint8_t pin, bool high) override { log.push_back((high ? "hi" : "lo") + std::to_string(pin)); }
  void release(uint8_t pin) override { log.push_back("rel" + std::to_string(pin)); }
  void delayMs(uint32_t ms) override { log.push_back("wait" + std::to_string(ms)); }
};

void test_generic_camera_pulses_the_pins_and_tracks_recording() {
  FakeClock clock;
  RamLog log(clock);
  FakeGpio gpio;
  {
    GenericCamera cam(gpio, log, 25, 23);
    TEST_ASSERT_TRUE(cam.ready());
    cam.wake();
    cam.start();
    cam.start();  // already recording: ignored
    TEST_ASSERT_TRUE(cam.recording());
    cam.stop();
    cam.stop();
    TEST_ASSERT_FALSE(cam.recording());
  }
  std::vector<std::string> want = {"out25", "out23", "hi23", "wait100", "lo23", "hi25", "wait100", "lo25", "hi25", "wait100", "lo25", "rel23", "rel25"};
  TEST_ASSERT_TRUE(gpio.log == want);
}

void test_generic_camera_uses_the_release_pin_for_focus_when_there_is_none() {
  FakeClock clock;
  RamLog log(clock);
  FakeGpio gpio;
  GenericCamera cam(gpio, log, 25, kNoPin);
  cam.wake();
  std::vector<std::string> want = {"out25", "hi25", "wait100", "lo25"};
  TEST_ASSERT_TRUE(gpio.log == want);
  GenericCamera none(gpio, log, kNoPin, kNoPin);
  TEST_ASSERT_FALSE(none.ready());
}

struct FakeHttp : Http {
  bool link = true;
  std::vector<std::string> requests;
  std::map<std::string, std::string> replies;  // path -> body ("" = no answer)
  int failFirst = 0;
  bool linkUp() override { return link; }
  HttpResult get(const std::string& host, const std::string& path, uint32_t) override {
    requests.push_back(host + path);
    HttpResult r;
    if (failFirst > 0) { failFirst--; return r; }
    auto it = replies.find(path);
    if (it == replies.end() || it->second.empty()) return r;
    r.ok = true;
    r.status = 200;
    r.body = it->second;
    return r;
  }
};

void test_zcam_calls_the_same_endpoints_as_the_node_driver() {
  FakeClock clock;
  RamLog log(clock);
  FakeHttp http;
  const char* ok = "{\"code\":0,\"msg\":\"\"}";
  http.replies["/ctrl/session"] = ok;
  http.replies["/ctrl/mode?action=to_standby"] = ok;
  http.replies["/ctrl/rec?action=start"] = ok;
  http.replies["/ctrl/rec?action=stop"] = ok;
  http.replies["/ctrl/rec?action=remain"] = "{\"code\":0,\"msg\":\"42.5\"}";
  uint8_t ip[4] = {192, 168, 1, 50};
  ZCamCamera cam(http, log, ip);
  cam.wake();
  cam.start();
  TEST_ASSERT_TRUE(cam.recording());
  cam.stop();
  TEST_ASSERT_FALSE(cam.recording());
  cam.sleep();
  TEST_ASSERT_TRUE(cam.ready());
  std::vector<std::string> want = {
      "192.168.1.50/ctrl/session", "192.168.1.50/ctrl/rec?action=remain", "192.168.1.50/ctrl/rec?action=start",
      "192.168.1.50/ctrl/rec?action=stop", "192.168.1.50/ctrl/rec?action=remain", "192.168.1.50/ctrl/mode?action=to_standby"};
  TEST_ASSERT_TRUE(http.requests == want);
}

void test_zcam_retries_start_and_stop_once_and_reports_not_ready_without_a_link() {
  FakeClock clock;
  RamLog log(clock);
  FakeHttp http;
  http.replies["/ctrl/rec?action=start"] = "{\"code\":0,\"msg\":\"\"}";
  uint8_t ip[4] = {10, 0, 0, 7};
  ZCamCamera cam(http, log, ip);
  http.failFirst = 2;  // the remain call, then the first start
  cam.start();
  TEST_ASSERT_TRUE(cam.recording());  // the retry worked

  FakeHttp down;
  ZCamCamera cam2(down, log, ip);
  cam2.wake();
  TEST_ASSERT_TRUE(cam2.ready());  // a failed call does not latch: the link is what matters
  down.link = false;
  TEST_ASSERT_FALSE(cam2.ready());
  TEST_ASSERT_FALSE(cam2.recording());
}

void test_zcam_replies_are_parsed() {
  int code = -1;
  std::string msg;
  TEST_ASSERT_TRUE(parseZcamReply("{\"code\":0,\"msg\":\"hello\"}", code, msg));
  TEST_ASSERT_EQUAL_INT(0, code);
  TEST_ASSERT_EQUAL_STRING("hello", msg.c_str());
  TEST_ASSERT_TRUE(parseZcamReply("{ \"code\" : 7 , \"msg\" : \"Busy\" }", code, msg));
  TEST_ASSERT_EQUAL_INT(7, code);
  TEST_ASSERT_EQUAL_STRING("Busy", msg.c_str());
  TEST_ASSERT_FALSE(parseZcamReply("<html>", code, msg));
}

void test_next_event_picks_the_earliest_across_attempts() {
  Device d;
  d.begin();
  Config c = d.config();
  c.attempts = {Attempt{kNowSec + 7200, "late"}, Attempt{kNowSec + 3600, "early"}};
  d.controller.saveConfig(c);
  NextEvent n = d.scheduler.next();
  TEST_ASSERT_TRUE(n.valid);
  TEST_ASSERT_EQUAL_UINT8(1, n.index);  // the index in the config's list, not time order
  TEST_ASSERT_TRUE(n.type == EventType::Wake);
  TEST_ASSERT_TRUE(d.scheduler.nextDueMs() == n.atMs);
}

// ---------------------------------------------------------------------------

void setUp() {}
void tearDown() {}

static bool loadVectors() {
  const char* paths[] = {VECTORS_PATH, "docs/test-vectors.json", "../../docs/test-vectors.json", "../docs/test-vectors.json"};
  for (const char* p : paths) {
    if (loadJsonFile(p, g_vectors)) return true;
  }
  return false;
}

int main(int, char**) {
  UNITY_BEGIN();
  if (!loadVectors()) {
    std::printf("cannot read docs/test-vectors.json\n");
    return 1;
  }
  RUN_TEST(test_sha256_and_hmac);
  RUN_TEST(test_vectors_auth);
  RUN_TEST(test_vectors_framing);
  RUN_TEST(test_vectors_status_and_device_info);
  RUN_TEST(test_vectors_config);
  RUN_TEST(test_vectors_messages);
  RUN_TEST(test_vectors_errors);
  RUN_TEST(test_a_short_or_malformed_request_is_refused);

  RUN_TEST(test_hello_reports_pairing_and_a_fresh_nonce);
  RUN_TEST(test_everything_but_the_open_ops_needs_auth);
  RUN_TEST(test_unknown_ops_are_not_supported);
  RUN_TEST(test_pairing_needs_the_window_then_stores_the_secret);
  RUN_TEST(test_the_pairing_window_closes_on_its_own);
  RUN_TEST(test_auth_verifies_both_directions);
  RUN_TEST(test_auth_before_hello_or_when_unpaired_fails);
  RUN_TEST(test_five_failures_lock_auth_out);
  RUN_TEST(test_the_lockout_doubles_up_to_five_minutes);
  RUN_TEST(test_status_carries_phase_next_event_and_attempt);
  RUN_TEST(test_config_set_replaces_saves_and_reschedules);
  RUN_TEST(test_config_set_validation_names_the_field_and_changes_nothing);
  RUN_TEST(test_a_config_with_too_many_attempts_or_a_wrong_layout_is_a_bad_request);
  RUN_TEST(test_a_full_config_fragments_across_a_23_byte_mtu);
  RUN_TEST(test_time_set_applies_the_clock_reschedules_and_reports_drift);
  RUN_TEST(test_manual_camera_control_is_idempotent_and_refused_while_busy);
  RUN_TEST(test_a_scheduled_attempt_walks_its_phases_and_blocks_changes);
  RUN_TEST(test_attempts_whose_wake_time_has_passed_are_missed);
  RUN_TEST(test_nothing_is_armed_until_the_clock_is_valid);
  RUN_TEST(test_a_completed_attempt_stays_complete_when_the_schedule_is_replanned);
  RUN_TEST(test_a_missed_attempt_is_announced_once);
  RUN_TEST(test_run_test_streams_progress_and_finishes_idle);
  RUN_TEST(test_logs_get_pages_by_seq_and_subscribe_replays);
  RUN_TEST(test_long_log_lines_are_cut);
  RUN_TEST(test_the_log_keeps_the_last_500_lines);
  RUN_TEST(test_events_only_reach_authenticated_links);
  RUN_TEST(test_malformed_input_gets_an_error_not_a_crash);
  RUN_TEST(test_a_stalled_message_times_out);
  RUN_TEST(test_messages_for_an_unsubscribed_client_are_dropped);
  RUN_TEST(test_an_oversized_response_becomes_too_large);

  RUN_TEST(test_defaults_when_nothing_is_stored);
  RUN_TEST(test_the_config_and_secret_survive_a_restart);
  RUN_TEST(test_a_corrupt_stored_config_falls_back_to_the_defaults_and_keeps_the_secret);
  RUN_TEST(test_a_failed_save_keeps_the_old_config_and_schedule);
  RUN_TEST(test_a_config_change_is_announced);
  RUN_TEST(test_a_camera_that_is_not_ready_is_an_error_phase);

  RUN_TEST(test_names_are_limited_in_bytes);
  RUN_TEST(test_advertised_names_keep_the_id);
  RUN_TEST(test_device_ids);
  RUN_TEST(test_generic_camera_pulses_the_pins_and_tracks_recording);
  RUN_TEST(test_generic_camera_uses_the_release_pin_for_focus_when_there_is_none);
  RUN_TEST(test_zcam_calls_the_same_endpoints_as_the_node_driver);
  RUN_TEST(test_zcam_retries_start_and_stop_once_and_reports_not_ready_without_a_link);
  RUN_TEST(test_zcam_replies_are_parsed);
  RUN_TEST(test_next_event_picks_the_earliest_across_attempts);
  return UNITY_END();
}
