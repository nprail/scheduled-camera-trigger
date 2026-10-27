# A USB upload must start the firmware it writes. After an update over Bluetooth the device boots the other app
# slot (app1), which `upload` does not touch, so the new USB image would never run. Writing a blank `otadata`
# (0x11000, 8 KB, in both partition tables) makes the bootloader start the first slot again. NVS is not touched,
# so the pairing and the schedule are kept.
import os

Import("env")  # noqa: F821  (provided by PlatformIO)

OTADATA_OFFSET = "0x11000"
OTADATA_SIZE = 0x2000

build_dir = env.subst("$BUILD_DIR")  # noqa: F821
os.makedirs(build_dir, exist_ok=True)
blank = os.path.join(build_dir, "otadata_blank.bin")
with open(blank, "wb") as f:
    f.write(b"\xff" * OTADATA_SIZE)

# the platform has already built the esptool arguments by the time a post script runs: add the image to them
env.Append(UPLOADERFLAGS=[OTADATA_OFFSET, blank])  # noqa: F821
