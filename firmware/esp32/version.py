# Sets FW_VERSION, the string the device reports as `fw` in Device Info.
# KAIROS_VERSION wins (the release workflow sets it from the tag), then `git describe`, then "dev".
import os
import subprocess

Import("env")  # noqa: F821  (provided by PlatformIO)


def version():
    v = os.environ.get("KAIROS_VERSION", "").strip().lstrip("v")
    if v:
        return v
    try:
        out = subprocess.run(
            ["git", "describe", "--tags", "--always", "--dirty"],
            cwd=env["PROJECT_DIR"], capture_output=True, text=True, check=True,  # noqa: F821
        ).stdout.strip()
        if out:
            return out.lstrip("v")
    except Exception:
        pass
    return "dev"


env.Append(CPPDEFINES=[("FW_VERSION", env.StringifyMacro(version()))])  # noqa: F821
