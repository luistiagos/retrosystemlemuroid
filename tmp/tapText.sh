#!/bin/bash
export MSYS2_ARG_CONV_EXCL='*'
adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
adb pull /sdcard/w.xml ./tmp/w.xml >/dev/null 2>&1
line=$(grep -o 'text="[^"]*" [^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' ./tmp/w.xml | grep -F "text=\"$1\"" | head -1)
if [ -z "$line" ]; then echo "NOT FOUND: $1"; exit 1; fi
b=$(echo "$line" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*')
set -- $b
x=$(( ($1 + $3) / 2 )); y=$(( ($2 + $4) / 2 ))
echo "tap ($x,$y)"
adb shell input tap $x $y
