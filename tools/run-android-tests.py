"""Run the offline regression suite on an explicitly selected test device."""
import argparse
from pathlib import Path
import re
import subprocess
import sys


EXCLUDED = [
    "PhoneStandaloneTest", "PhoneSourceCaptureTest", "MobileStandaloneTest",
    "MobilePayloadCaptureTest", "MobileExtrasCaptureTest", "MobileWorkflowTest",
    "MobileMediaProbeTest", "BrowserMediaProbeTest", "BrowserArtworkSessionTest",
    "BrowseNetworkTest", "WebsiteNewsNetworkTest", "data.ImageCacheTest",
    "PhonePreviewProbeTest", "PhoneSideStatisticsTest",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial of a dedicated test device")
    parser.add_argument("--abi", choices=["x86_64", "arm64-v8a", "armeabi-v7a"], default="x86_64")
    parser.add_argument("--install", action="store_true", help="Install the built app and test APKs first")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    adb = ["adb", "-s", args.serial]
    subprocess.run(adb + ["get-state"], check=True)
    if args.install:
        for name in [f"debug/app-{args.abi}-debug.apk", "androidTest/debug/app-debug-androidTest.apk"]:
            apk = root / "app/build/outputs/apk" / name
            if not apk.is_file():
                parser.error(f"Missing APK: {apk}. Build assembleDebug and assembleDebugAndroidTest first.")
            subprocess.run(adb + ["install", "-r", str(apk)], check=True)
    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "notClass",
                     ",".join("dev.matchscope.app." + name for name in EXCLUDED),
                     "dev.matchscope.app.test/androidx.test.runner.AndroidJUnitRunner"]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    output = result.stdout + result.stderr
    print(output, end="")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    # ADB can exit successfully even when instrumentation reports a failure.
    passed = re.search(r"OK \(([1-9][0-9]*) tests?\)", output)
    if result.returncode or not passed or any(marker in output for marker in
                                             ["FAILURES!!!", "Process crashed", "INSTRUMENTATION_FAILED"]):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
