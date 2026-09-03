#!/bin/bash
# $1 = option label
export MSYS2_ARG_CONV_EXCL='*'
./tmp/tapText.sh "Configurações" >/dev/null 2>&1
adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1
# open settings via gear: find by content-desc fallback -> use fixed ratio of screen width
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | head -1 | cut -dx -f1)
H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | head -1 | cut -dx -f2)
