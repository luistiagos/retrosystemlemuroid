#!/bin/bash
export MSYS2_ARG_CONV_EXCL='*'
./tmp/tapDesc.sh "Buscar" >/dev/null 2>&1; sleep 3
adb shell input tap 500 215; sleep 1
adb shell input text "Super%sMario%sWorld"; sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
adb pull /sdcard/w.xml ./tmp/w.xml >/dev/null 2>&1
coords=$(grep -o 'text="Super Mario World" [^>]*bounds="[^"]*"' ./tmp/w.xml \
  | grep -o 'bounds="[^"]*"' | sed 's/[^0-9]\+/ /g' \
  | awk '{ if ($1 < 300) { print int(($1+$3)/2), int(($2+$4)/2); exit } }')
if [ -z "$coords" ]; then echo "no result row found"; exit 1; fi
echo "launch tap ($coords)"
adb shell input tap $coords
sleep 12
adb shell dumpsys activity activities 2>/dev/null | grep -o "topResumedActivity=.*GameActivity" | head -1
