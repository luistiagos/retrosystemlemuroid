#!/bin/bash
# $1 = dialog option label, $2 = output png basename
export MSYS2_ARG_CONV_EXCL='*'
adb shell settings put system user_rotation 0; sleep 3     # portrait for UI
adb shell input keyevent KEYCODE_BACK; sleep 5             # exit game
./tmp/setaspect.sh "$1" || exit 1
./tmp/launchsmw.sh || exit 1
sleep 2
adb exec-out screencap -p > "tmp/$2-portrait.png"
adb shell settings put system user_rotation 1; sleep 4
adb exec-out screencap -p > "tmp/$2-land.png"
echo "captured $2"
