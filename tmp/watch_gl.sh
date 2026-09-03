#!/bin/bash
export MSYS2_ARG_CONV_EXCL='*'
out=tmp/gl-watch.log
: > $out
for i in $(seq 1 40); do
  adb logcat -c 2>/dev/null
  sleep 8
  n=$(adb logcat -d 2>/dev/null | grep -cE 'EMUFPS|VIDEOFRAMES')
  act=$(adb shell dumpsys activity activities 2>/dev/null | grep -oE "ResumedActivity: [^ ]* [^ ]* [^ ]*/[^ ]*" | head -1)
  echo "$(date +%H:%M:%S) frames=$n act=$act" >> $out
  if [ "$n" = "0" ]; then echo "STALL DETECTED at $(date +%H:%M:%S)" >> $out; break; fi
done
echo DONE >> $out
