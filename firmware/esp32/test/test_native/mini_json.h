// A tiny JSON reader for protocol/test-vectors.json (objects, arrays, numbers, strings, booleans, null). Test support only.
#pragma once
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

struct Json {
  enum Type { Null, Bool, Number, String, Array, Object } type = Null;
  bool b = false;
  double n = 0;
  std::string s;
  std::vector<Json> a;
  std::vector<std::pair<std::string, Json>> o;

  const Json& operator[](const std::string& key) const {
    static const Json none;
    for (const auto& kv : o) {
      if (kv.first == key) return kv.second;
    }
    return none;
  }
  const Json& operator[](size_t i) const { return a.at(i); }
  size_t size() const { return type == Array ? a.size() : o.size(); }
  bool has(const std::string& key) const {
    for (const auto& kv : o) {
      if (kv.first == key) return true;
    }
    return false;
  }
  bool isNull() const { return type == Null; }
  uint64_t u64() const { return static_cast<uint64_t>(n); }
  int64_t i64() const { return static_cast<int64_t>(n); }
};

class JsonParser {
 public:
  explicit JsonParser(const std::string& text) : t_(text) {}
  Json parse() {
    Json v = value();
    return v;
  }

 private:
  void ws() {
    while (p_ < t_.size() && (t_[p_] == ' ' || t_[p_] == '\n' || t_[p_] == '\r' || t_[p_] == '\t')) p_++;
  }
  std::string str() {
    std::string out;
    p_++;  // opening quote
    while (p_ < t_.size() && t_[p_] != '"') {
      char c = t_[p_++];
      if (c != '\\') { out.push_back(c); continue; }
      char e = t_[p_++];
      switch (e) {
        case 'n': out.push_back('\n'); break;
        case 't': out.push_back('\t'); break;
        case 'r': out.push_back('\r'); break;
        case 'b': out.push_back('\b'); break;
        case 'f': out.push_back('\f'); break;
        case 'u': {
          unsigned cp = static_cast<unsigned>(std::strtoul(t_.substr(p_, 4).c_str(), nullptr, 16));
          p_ += 4;
          if (cp >= 0xD800 && cp <= 0xDBFF && t_.compare(p_, 2, "\\u") == 0) {  // surrogate pair
            unsigned lo = static_cast<unsigned>(std::strtoul(t_.substr(p_ + 2, 4).c_str(), nullptr, 16));
            p_ += 6;
            cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
          }
          if (cp < 0x80) out.push_back(static_cast<char>(cp));
          else if (cp < 0x800) { out.push_back(static_cast<char>(0xC0 | (cp >> 6))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
          else if (cp < 0x10000) { out.push_back(static_cast<char>(0xE0 | (cp >> 12))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
          else { out.push_back(static_cast<char>(0xF0 | (cp >> 18))); out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
          break;
        }
        default: out.push_back(e); break;
      }
    }
    p_++;  // closing quote
    return out;
  }
  Json value() {
    ws();
    Json v;
    char c = t_[p_];
    if (c == '{') {
      v.type = Json::Object;
      p_++;
      ws();
      while (t_[p_] != '}') {
        ws();
        std::string key = str();
        ws();
        p_++;  // colon
        v.o.emplace_back(key, value());
        ws();
        if (t_[p_] == ',') p_++;
        ws();
      }
      p_++;
    } else if (c == '[') {
      v.type = Json::Array;
      p_++;
      ws();
      while (t_[p_] != ']') {
        v.a.push_back(value());
        ws();
        if (t_[p_] == ',') p_++;
        ws();
      }
      p_++;
    } else if (c == '"') {
      v.type = Json::String;
      v.s = str();
    } else if (t_.compare(p_, 4, "true") == 0) {
      v.type = Json::Bool; v.b = true; p_ += 4;
    } else if (t_.compare(p_, 5, "false") == 0) {
      v.type = Json::Bool; v.b = false; p_ += 5;
    } else if (t_.compare(p_, 4, "null") == 0) {
      v.type = Json::Null; p_ += 4;
    } else {
      v.type = Json::Number;
      char* end = nullptr;
      v.n = std::strtod(t_.c_str() + p_, &end);
      p_ = static_cast<size_t>(end - t_.c_str());
    }
    return v;
  }
  const std::string& t_;
  size_t p_ = 0;
};

inline bool loadJsonFile(const char* path, Json& out) {
  std::ifstream f(path);
  if (!f) return false;
  std::stringstream ss;
  ss << f.rdbuf();
  std::string text = ss.str();
  out = JsonParser(text).parse();
  return true;
}
