#!/bin/bash
# $1 = option label in the dialog
export MSYS2_ARG_CONV_EXCL='*'
./tmp/tapDesc.sh "Configurações" >/dev/null || exit 1
sleep 3
for i in 1 2 3 4 5 6; do
  if ./tmp/tapText.sh "Proporção da tela" >/dev/null 2>&1; then break; fi
  adb shell input swipe 600 900 600 400 250; sleep 1
done
sleep 2
./tmp/tapText.sh "$1" || exit 1
sleep 2
adb shell "run-as app.retrogamesystem.debug cat files/harmony_prefs/harmony_options/prefs.transaction.data" 2>/dev/null | tr -d '\0' | grep -ao "screen_aspect_ratio[a-z0-9:]*" | tail -1
adb shell input keyevent KEYCODE_BACK; sleep 2
