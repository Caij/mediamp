#!/usr/bin/env python3
"""Check a packaged MPV runtime before artifact upload or Maven publication."""

import argparse
from pathlib import Path
from zipfile import ZipFile


PLATFORMS = (
    "windows-x64", "windows-arm64", "macos-x64", "macos-arm64", "linux-x64",
)


def verify_runtime(jar: Path, platform: str) -> None:
    manifest_name = f"mpv-natives-{platform}.txt"
    os_name = platform.split("-", 1)[0]
    wrapper = {
        "windows": "mediampv.dll",
        "macos": "libmediampv.dylib",
        "linux": "libmediampv.so",
    }[os_name]
    with ZipFile(jar) as archive:
        bad_entry = archive.testzip()
        if bad_entry is not None:
            raise ValueError(f"Corrupt JAR entry: {bad_entry}")
        libraries = archive.read(manifest_name).decode("utf-8").splitlines()
        if not libraries or len(libraries) != len(set(libraries)):
            raise ValueError("Runtime manifest is empty or contains duplicate entries")
        for library in libraries:
            if archive.getinfo(library).file_size == 0:
                raise ValueError(f"Empty native library: {library}")
        if wrapper not in libraries:
            raise ValueError(f"JNI wrapper missing from manifest: {wrapper}")
        # Confirm this is a complete MPV runtime, including the bundled FFmpeg libs.
        for family in ("mpv", "avcodec", "avformat", "avutil", "swresample", "swscale"):
            def matches(name: str) -> bool:
                if os_name == "windows":
                    return name.endswith(".dll") and name.startswith((f"{family}-", f"lib{family}-"))
                if os_name == "macos":
                    return name.startswith(f"lib{family}.") and name.endswith(".dylib")
                return name == f"lib{family}.so" or name.startswith(f"lib{family}.so.")

            if not any(matches(Path(library).name) for library in libraries):
                raise ValueError(f"Native library family missing from manifest: {family}")
    print(f"Verified {platform}: {jar.name} ({len(libraries)} native libraries)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("platform", choices=PLATFORMS)
    args = parser.parse_args()
    verify_runtime(args.jar, args.platform)
