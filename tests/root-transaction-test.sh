#!/system/bin/sh
# Isolated ordinary files, copied hardware frequency tables. No CPU/GPU/cpuset device node is written.
set -eu
src=$1
f=/data/local/tmp/qt-global3-fixture
[ ! -f "$f/lease" ] || { echo 'Unfinished fixture transaction'; exit 1; }
mkdir -p "$f/cpu" "$f/gpu" "$f/sets"
sed -e "s#/data/adb/apptuner9400-v3#$f#g" -e "s#CPU=/sys/devices/system/cpu/cpufreq#CPU=$f/cpu#" -e "s#GPU=/sys/kernel/ged/hal#GPU=$f/gpu#" -e "s#OPP=/proc/gpufreqv2/stack_working_opp_table#OPP=$f/opp#" -e "s#SETS=/dev/cpuset#SETS=$f/sets#" -e "s#/data/adb/apptuner9400/lease#$f/legacy#" -e "s#/data/adb/apptuner9400/phase#$f/legacy-phase#" "$src" > "$f/controller.sh"
grep -q "CPU=$f/cpu" "$f/controller.sh"; grep -q "GPU=$f/gpu" "$f/controller.sh"
for id in 0 4 7; do
    mkdir -p "$f/cpu/policy$id"
    for n in related_cpus cpuinfo_max_freq scaling_available_frequencies scaling_governor; do cat "/sys/devices/system/cpu/cpufreq/policy$id/$n" > "$f/cpu/policy$id/$n"; done
done
cat /proc/gpufreqv2/stack_working_opp_table > "$f/opp"
echo 339000 > "$f/cpu/policy0/scaling_min_freq"; echo 1200000 > "$f/cpu/policy0/scaling_max_freq"
echo 622000 > "$f/cpu/policy4/scaling_min_freq"; echo 1800000 > "$f/cpu/policy4/scaling_max_freq"
echo 798000 > "$f/cpu/policy7/scaling_min_freq"; echo 2800000 > "$f/cpu/policy7/scaling_max_freq"
echo 51 > "$f/gpu/custom_boost_gpu_freq"; echo 32 > "$f/gpu/custom_upbound_gpu_freq"
for g in background system-background foreground top-app; do mkdir -p "$f/sets/$g"; done
echo 0-3 > "$f/sets/background/cpus"; echo 0-3 > "$f/sets/system-background/cpus"; echo 0-7 > "$f/sets/foreground/cpus"; echo 4-7 > "$f/sets/top-app/cpus"
h=$f/controller.sh
trap 'sh "$h" restore >/dev/null 2>&1' EXIT
A='339000 1400000 622000 2000000 798000 3000000 338000 806000 15 15 255 255'
B='339000 1600000 622000 1800000 798000 2800000 338000 884000 255 255 255 255'
sh "$h" trial global $A
sh "$h" confirm "$(sed -n 2p "$f/lease")"
sh "$h" trial global $B
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1600000 ]
sleep 22
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1400000 ]
[ "$(cat "$f/gpu/custom_upbound_gpu_freq")" = 31 ]
[ "$(cat "$f/phase")" = active ]
echo PASS_REPLACE_AND_TIMEOUT_TO_CONFIRMED
sh "$h" restore
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1200000 ]
[ "$(cat "$f/gpu/custom_upbound_gpu_freq")" = 32 ]
[ ! -f "$f/lease" ]
echo PASS_ORIGINAL_BASELINE_RETAINED
sh "$h" trial global $A
sh "$h" trial global $B
sleep 22
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1200000 ]
[ ! -f "$f/lease" ]
echo PASS_UNCONFIRMED_REPLACEMENT
# Full supported minimum, one core per group; model test only.
sh "$h" trial global 339000 339000 622000 622000 798000 798000 338000 338000 128 128 128 128
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 339000 ]
[ "$(cat "$f/sets/system-background/cpus")" = 7 ]
sh "$h" rollback
printf '* %s\ncom.up366.mobile %s\n' "$A" "$B" > "$f/rules"
sh "$h" switch com.up366.mobile
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1600000 ]
sh "$h" switch com.other.app
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1400000 ]
echo PASS_APP_RULE_AND_GLOBAL_FALLBACK
# Transient boost must not replace the intent or accuse an external controller.
echo 800000 > "$f/cpu/policy0/scaling_min_freq"
sh "$h" probe > "$f/sample"
grep -q '^phase=active$' "$f/sample"; grep -q '^match=0$' "$f/sample"
echo 339000 > "$f/cpu/policy0/scaling_min_freq"
sh "$h" probe > "$f/sample"; grep -q '^match=1$' "$f/sample"
echo PASS_TRANSIENT_READING_RECOVERY
# Retain a value replaced by another writer while restoring owned resources.
echo 1500000 > "$f/cpu/policy0/scaling_max_freq"
sh "$h" restore
[ "$(cat "$f/cpu/policy0/scaling_max_freq")" = 1500000 ]
[ "$(cat "$f/gpu/custom_upbound_gpu_freq")" = 32 ]
echo PASS_EXTERNAL_VALUE_RETAINED
chmod 444 "$f/gpu/custom_upbound_gpu_freq"
sh "$h" trial global $A
[ "$(stat -c %a "$f/gpu/custom_upbound_gpu_freq")" = 444 ]
sh "$h" rollback
[ "$(stat -c %a "$f/gpu/custom_upbound_gpu_freq")" = 444 ]
echo PASS_GPU_PERMISSION_RESTORED
echo > "$f/sets/foreground/cpus"
if sh "$h" trial global $A; then echo 'Incomplete baseline accepted'; exit 1; fi
[ ! -f "$f/lease" ]
echo 0-7 > "$f/sets/foreground/cpus"
echo PASS_INCOMPLETE_BASELINE_REJECTED
