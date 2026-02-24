#!/usr/bin/env python3
import subprocess
from pathlib import Path


ROOT = Path("/Users/a/sms-relay-mvp/android-app")
SVG_FILE = ROOT / "app/src/main/res/tang_icon.svg"
TMP_PNG = Path("/tmp/tang_icon.png")
SIZES = [
    (48, "mipmap-mdpi"),
    (72, "mipmap-hdpi"),
    (96, "mipmap-xhdpi"),
    (144, "mipmap-xxhdpi"),
    (192, "mipmap-xxxhdpi"),
]


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def main() -> None:
    run(["sips", "-s", "format", "png", str(SVG_FILE), "--out", str(TMP_PNG)])

    for size, mipmap_dir in SIZES:
        base_path = ROOT / "app/src/main/res" / mipmap_dir
        launcher_path = base_path / "ic_launcher.png"
        round_path = base_path / "ic_launcher_round.png"

        run(["sips", "-z", str(size), str(size), str(TMP_PNG), "--out", str(launcher_path)])
        run(["sips", "-z", str(size), str(size), str(TMP_PNG), "--out", str(round_path)])

        print(f"Created {size}x{size} PNG: {launcher_path}")
        print(f"Created {size}x{size} PNG: {round_path}")

    print("All icons generated successfully!")


if __name__ == "__main__":
    main()
