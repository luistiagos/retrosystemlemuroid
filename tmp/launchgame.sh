#!/bin/bash
# $1 = adb-escaped search text, $2 = exact title text to tap
export MSYS2_ARG_CONV_EXCL='*'
./tmp/tapDesc.sh "Buscar" >/dev/null 2>&1; sleep 2
adb shell input tap 500 215; sleep 1
adb shell input keyevent KEYCODE_MOVE_END; sleep 1
for i in $(seq 1 45); do adb shell input keyevent KEYCODE_DEL; done; sleep 2
adb shell input text "$1"; sleep 4
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
adb pull /sdcard/w.xml ./tmp/w.xml >/dev/null 2>&1
grep -o 'text="[^"]*" [^>]*bounds="[^"]*"' ./tmp/w.xml | grep -oE 'text="[^"]*"|bounds="[^"]*"' | paste - - | grep -v 'text=""' | head -10
