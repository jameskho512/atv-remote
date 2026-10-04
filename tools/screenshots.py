"""Regenerates the README screenshots from a debug build installed on a USB-connected phone.

The app's demo mode (debug builds only) fills the screens with placeholder data, so no real devices,
titles or artwork appear. Requires adb on PATH and Pillow (pip install pillow).

    gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
    python tools/screenshots.py
"""
import io
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "docs" / "screenshots"
STATUS_BAR_PX = 150   # cropped off the top: clock, notification icons, battery
WIDTH = 540           # README width; the phone's native 1280 would make the repo heavy

# (file name, demo screen, steps before capturing). A step is ("tap", text) or ("wait", seconds).
SHOTS = [
    ("apple-tv", "apple", []),
    ("apple-tv-siri-layout", "apple-siri", []),
    ("bravia", "bravia", []),
    ("bravia-picture-mode", "bravia", [("tap", "Dolby Vision"), ("wait", 1.5)]),
]


def adb(*args: str, binary: bool = False):
    out = subprocess.run(["adb", *args], check=True, capture_output=True).stdout
    return out if binary else out.decode()


def find_center(attr: str, value: str):
    for _ in range(8):  # the screen may still be drawing
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        for node in ET.fromstring(xml).iter("node"):
            if node.get(attr) == value:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(1)
    raise SystemExit(f"nothing on screen with {attr}={value!r}")


def app_in_front() -> bool:
    """False on the lock screen or when another app covers ours."""
    win = adb("shell", "dumpsys", "window")
    focus = next((l for l in win.splitlines() if "mCurrentFocus" in l), "")
    return "app.atvremote" in focus and "isKeyguardShowing=true" not in win and "mDreamingLockscreen=true" not in win


def run_step(kind: str, arg) -> None:
    if kind == "wait":
        time.sleep(arg)
    elif kind == "tap":
        x, y = find_center("text", arg)
        adb("shell", "input", "tap", str(x), str(y))
    else:
        raise SystemExit(f"unknown step {kind!r}")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    for name, screen, steps in SHOTS:
        adb("shell", "am", "force-stop", "app.atvremote")
        adb("shell", "am", "start", "-n", "app.atvremote/.MainActivity", "-e", "demo", screen)
        time.sleep(4)
        for kind, arg in steps:
            run_step(kind, arg)
        if not app_in_front():
            raise SystemExit(f"{name}: the app isn't on screen (is the phone locked?); nothing saved")
        img = Image.open(io.BytesIO(adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")
        img = img.crop((0, STATUS_BAR_PX, img.width, img.height))
        img = img.resize((WIDTH, round(img.height * WIDTH / img.width)), Image.LANCZOS)
        img.save(OUT / f"{name}.png", optimize=True)
        print("saved", name)
    adb("shell", "rm", "/sdcard/ui.xml")
    adb("shell", "am", "force-stop", "app.atvremote")


if __name__ == "__main__":
    main()
