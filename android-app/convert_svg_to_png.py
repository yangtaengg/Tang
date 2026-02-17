#!/usr/bin/env python3
from svglib.svglib import svg2rlg
from reportlab.graphics import renderPM

svg_file = "/Users/a/sms-relay-mvp/android-app/app/src/main/res/tang_icon.svg"

sizes = [
    (48, "/Users/a/sms-relay-mvp/android-app/app/src/main/res/mipmap-mdpi/ic_launcher.png"),
    (72, "/Users/a/sms-relay-mvp/android-app/app/src/main/res/mipmap-hdpi/ic_launcher.png"),
    (96, "/Users/a/sms-relay-mvp/android-app/app/src/main/res/mipmap-xhdpi/ic_launcher.png"),
    (144, "/Users/a/sms-relay-mvp/android-app/app/src/main/res/mipmap-xxhdpi/ic_launcher.png"),
    (192, "/Users/a/sms-relay-mvp/android-app/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"),
]

for size, output_file in sizes:
    drawing = svg2rlg(svg_file)
    if drawing:
        renderPM.drawToFile(drawing, output_file, fmt="PNG", dpi=96*size/512)
        print(f"Created {size}x{size} PNG: {output_file}")

print("All icons generated successfully!")
