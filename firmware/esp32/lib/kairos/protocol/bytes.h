// Little-endian byte writer and bounds-checked reader (docs/ble-protocol.md, Types).
// Pure C++: no Arduino or ESP-IDF includes.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace kairos {

using Bytes = std::vector<uint8_t>;

class Writer {
 public:
  Writer& u8(uint8_t v) { b_.push_back(v); return *this; }
  Writer& u16(uint16_t v) { return u8(v & 0xff).u8(v >> 8); }
  Writer& u32(uint32_t v) { return u16(v & 0xffff).u16(v >> 16); }
  Writer& i32(int32_t v) { return u32(static_cast<uint32_t>(v)); }
  Writer& u64(uint64_t v) { return u32(static_cast<uint32_t>(v)).u32(static_cast<uint32_t>(v >> 32)); }
  Writer& raw(const uint8_t* p, size_t n) { b_.insert(b_.end(), p, p + n); return *this; }
  Writer& raw(const Bytes& v) { return raw(v.data(), v.size()); }
  /// A u8-length-prefixed string, cut to at most `max` bytes (callers validate the limits that matter).
  Writer& str(const std::string& s, size_t max = 255) {
    size_t n = s.size() < max ? s.size() : max;
    u8(static_cast<uint8_t>(n));
    return raw(reinterpret_cast<const uint8_t*>(s.data()), n);
  }
  const Bytes& bytes() const { return b_; }
  Bytes take() { return std::move(b_); }
  size_t size() const { return b_.size(); }

 private:
  Bytes b_;
};

/// Reads past the end set `ok()` to false and return zero, so a decoder can check once at the end.
class Reader {
 public:
  Reader(const uint8_t* p, size_t n) : p_(p), n_(n) {}
  explicit Reader(const Bytes& b) : Reader(b.data(), b.size()) {}

  uint8_t u8() { return need(1) ? p_[pos_++] : 0; }
  uint16_t u16() { uint16_t lo = u8(); uint16_t hi = u8(); return static_cast<uint16_t>(lo | (hi << 8)); }
  uint32_t u32() { uint32_t lo = u16(); uint32_t hi = u16(); return lo | (hi << 16); }
  int32_t i32() { return static_cast<int32_t>(u32()); }
  uint64_t u64() { uint64_t lo = u32(); uint64_t hi = u32(); return lo | (hi << 32); }
  void raw(uint8_t* out, size_t n) {
    if (!need(n)) { std::memset(out, 0, n); return; }
    std::memcpy(out, p_ + pos_, n);
    pos_ += n;
  }
  std::string str() {
    size_t n = u8();
    if (!need(n)) return std::string();
    std::string s(reinterpret_cast<const char*>(p_ + pos_), n);
    pos_ += n;
    return s;
  }
  bool ok() const { return ok_; }
  size_t remaining() const { return n_ - pos_; }
  const uint8_t* ptr() const { return p_ + pos_; }

 private:
  bool need(size_t n) {
    if (!ok_ || pos_ + n > n_) { ok_ = false; return false; }
    return true;
  }
  const uint8_t* p_;
  size_t n_;
  size_t pos_ = 0;
  bool ok_ = true;
};

/// Lowercase hex, for logs and test vectors.
inline std::string toHex(const uint8_t* p, size_t n) {
  static const char* d = "0123456789abcdef";
  std::string s;
  s.reserve(n * 2);
  for (size_t i = 0; i < n; i++) { s.push_back(d[p[i] >> 4]); s.push_back(d[p[i] & 15]); }
  return s;
}
inline std::string toHex(const Bytes& b) { return toHex(b.data(), b.size()); }

inline bool fromHex(const std::string& h, Bytes& out) {
  if (h.size() % 2) return false;
  out.clear();
  auto nib = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  };
  for (size_t i = 0; i < h.size(); i += 2) {
    int a = nib(h[i]), b = nib(h[i + 1]);
    if (a < 0 || b < 0) return false;
    out.push_back(static_cast<uint8_t>(a * 16 + b));
  }
  return true;
}

/// Cut a UTF-8 string to at most `max` bytes without splitting a character.
inline std::string truncateUtf8(const std::string& s, size_t max) {
  if (s.size() <= max) return s;
  size_t n = max;
  while (n > 0 && (static_cast<uint8_t>(s[n]) & 0xC0) == 0x80) n--;
  return s.substr(0, n);
}

}  // namespace kairos
