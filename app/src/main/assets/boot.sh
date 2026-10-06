#!/system/bin/sh
BASE=/data/adb/apptuner9400-v3
if [ -f "$BASE/persistent-enabled" ] && [ -f "$BASE/runtime.sh" ];then /system/bin/sh "$BASE/runtime.sh" boot;fi
