"""Tests for signing.py. Run with: python3 -m unittest discover -s firmware/esp32/tools"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).with_name("signing.py")


def run(*args, env=None, check=True):
    p = subprocess.run([sys.executable, str(TOOL), *args], capture_output=True, text=True, env={**os.environ, **(env or {})})
    if check and p.returncode != 0:
        raise AssertionError(p.stderr or p.stdout)
    return p


class SigningTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.key = self.dir / "key.pem"
        run("keygen", str(self.key))

    def image(self, name="kairos-esp32s3-1.3.0-app.bin", data=b"firmware" * 1000):
        f = self.dir / name
        f.write_bytes(data)
        return f

    def test_keygen_does_not_overwrite(self):
        self.assertNotEqual(run("keygen", str(self.key), check=False).returncode, 0)
        self.assertEqual(oct(self.key.stat().st_mode & 0o777), "0o600")

    def test_a_signature_is_64_bytes_and_verifies(self):
        f = self.image()
        run("--key", str(self.key), "sign", str(f))
        sig = Path(str(f) + ".sig")
        self.assertEqual(len(sig.read_bytes()), 64)
        run("verify", "--pub", str(self.key), str(f), str(sig))

    def test_a_modified_file_or_another_key_fails(self):
        f = self.image()
        run("--key", str(self.key), "sign", str(f))
        sig = Path(str(f) + ".sig")
        other = self.dir / "other.pem"
        run("keygen", str(other))
        self.assertNotEqual(run("verify", "--pub", str(other), str(f), str(sig), check=False).returncode, 0)
        f.write_bytes(f.read_bytes() + b"x")
        self.assertNotEqual(run("verify", "--pub", str(self.key), str(f), str(sig), check=False).returncode, 0)

    def test_the_key_can_come_from_the_environment(self):
        f = self.image()
        run("sign", str(f), env={"KAIROS_SIGNING_KEY": self.key.read_text()})
        run("verify", "--pub", str(self.key), str(f), str(f) + ".sig")

    def test_a_hex_signature_file_is_accepted(self):
        f = self.image()
        run("--key", str(self.key), "sign", str(f))
        sig = Path(str(f) + ".sig")
        hexed = self.dir / "hex.sig"
        hexed.write_text(sig.read_bytes().hex() + "\n")
        run("verify", "--pub", str(self.key), str(f), str(hexed))

    def test_the_header_holds_the_public_key(self):
        out = self.dir / "keys.h"
        run("--key", str(self.key), "header", str(out), str(self.key))
        text = out.read_text()
        self.assertIn("kSigningKeyCount = 1", text)
        self.assertIn("0x04", text)

    def test_the_manifest_lists_signed_images(self):
        s3 = self.image("kairos-esp32s3-1.3.0-app.bin", b"a" * 5000)
        c3 = self.image("kairos-esp32c3-1.3.0-app.bin", b"b" * 6000)
        run("--key", str(self.key), "sign", str(s3), str(c3))
        run("--key", str(self.key), "manifest", str(self.dir), "v1.3.0", "--min-from", "1.2.0", "--notes", "Fixes")
        m = json.loads((self.dir / "manifest.json").read_text())
        self.assertEqual((m["version"], m["protocol"], m["minFrom"], m["notes"]), ("1.3.0", 2, "1.2.0", "Fixes"))
        self.assertEqual(set(m["images"]), {"esp32s3", "esp32c3"})
        self.assertEqual(m["images"]["esp32s3"]["size"], 5000)
        self.assertEqual(len(m["images"]["esp32s3"]["sig"]), 128)
        self.assertEqual(len(m["images"]["esp32c3"]["sha256"]), 64)

    def test_the_manifest_refuses_a_bad_signature(self):
        s3 = self.image("kairos-esp32s3-1.3.0-app.bin", b"a" * 5000)
        run("--key", str(self.key), "sign", str(s3))
        s3.write_bytes(b"changed" * 100)
        self.assertNotEqual(run("--key", str(self.key), "manifest", str(self.dir), "1.3.0", check=False).returncode, 0)


if __name__ == "__main__":
    unittest.main()
