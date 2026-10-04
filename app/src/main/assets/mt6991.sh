#!/system/bin/sh
# MT6991 transactions. CPU/GED/cpuset only; system services and thermal protection stay running.
BASE=/data/adb/apptuner9400-v3
SELF=$BASE/controller.sh
CPU=/sys/devices/system/cpu/cpufreq
GPU=/sys/kernel/ged/hal
OPP=/proc/gpufreqv2/stack_working_opp_table
SETS=/dev/cpuset
GROUPS='background system-background foreground top-app'
umask 077
mkdir -p "$BASE" || exit 1
fail() { echo "ERROR: $*"; exit 1; }
now() { read -r t rest < /proc/uptime; echo "${t%.*}"; }
boot() { cat /proc/sys/kernel/random/boot_id; }
readnode() { read -r v rest < "$1" || return 1; echo "$v"; }
number() { case "$1" in ''|*[!0-9]*|0[0-9]*) return 1;; esac; [ "${#1}" -le 7 ]; }
mask() {
    input=$1; case "$input" in ''|*[!0-7,-]*) return 1;; esac
    result=0; oldifs=$IFS; IFS=,
    for part in $input; do
        case "$part" in *-*) first=${part%-*}; last=${part#*-};; *) first=$part; last=$part;; esac
        number "$first" && number "$last" && [ "$first" -le "$last" ] && [ "$last" -le 7 ] || { IFS=$oldifs; return 1; }
        while [ "$first" -le "$last" ]; do result=$((result | (1 << first))); first=$((first + 1)); done
    done
    IFS=$oldifs; echo "$result"
}
cpulist() { bits=$1; sep=; i=0; while [ "$i" -lt 8 ]; do if [ $((bits & (1 << i))) -ne 0 ]; then printf '%s%s' "$sep" "$i"; sep=,; fi; i=$((i+1)); done; echo; }
gpuindex() { awk -v f="$1" '$2=="freq:" {v=$3; sub(/,/,"",v); if(v==f){n=$1; gsub(/\[|\]/,"",n); found=n+0}} END{if(found!="") print found}' "$OPP"; }
gpufreq() { awk -v id="$1" '$2=="freq:" {n=$1; gsub(/\[|\]/,"",n); if(n+0==id){v=$3; sub(/,/,"",v); print v; exit}}' "$OPP"; }
gputable() { awk '$2=="freq:" {v=$3; sub(/,/,"",v); print v}' "$OPP" | sort -un | tr '\n' ' '; }
platform() {
    [ "$(getprop ro.board.platform)" = mt6991 ] || fail '仅支持 MT6991'
    [ "$(cat "$CPU/policy0/related_cpus")" = '0 1 2 3' ] && [ "$(cat "$CPU/policy4/related_cpus")" = '4 5 6' ] && [ "$(cat "$CPU/policy7/related_cpus")" = 7 ] || fail '核心拓扑不符合天玑 9400+'
    [ "$(readnode "$CPU/policy7/cpuinfo_max_freq")" = 3730000 ] && [ -r "$OPP" ] || fail '硬件接口不符合当前适配版本'
}
raw() {
    for id in 0 4 7; do printf '%s %s ' "$(readnode "$CPU/policy$id/scaling_min_freq")" "$(readnode "$CPU/policy$id/scaling_max_freq")"; done
    printf '%s %s' "$(readnode "$GPU/custom_boost_gpu_freq")" "$(readnode "$GPU/custom_upbound_gpu_freq")"
    for g in $GROUPS; do printf ' %s' "$(mask "$(cat "$SETS/$g/cpus")")"; done
    echo
}
logical() {
    set -- $1; printf '%s %s %s %s %s %s %s %s' "$1" "$2" "$3" "$4" "$5" "$6" "$(gpufreq "$7")" "$(gpufreq "$8")"; shift 8; printf ' %s' "$@"; echo
}
phase() { cat "$BASE/phase" 2>/dev/null; }
setphase() { echo "$1" > "$BASE/phase.tmp" && mv "$BASE/phase.tmp" "$BASE/phase"; }
load() {
    [ -f "$BASE/lease" ] || return 1
    { read -r savedboot; read -r token; read -r deadline; read -r original; read -r previous; read -r target; read -r previousphase; read -r actor; read -r previousactor; } < "$BASE/lease"
}
save() { printf '%s\n' "$savedboot" "$token" "$deadline" "$original" "$previous" "$target" "$previousphase" "$actor" "$previousactor" > "$BASE/lease.tmp" && mv "$BASE/lease.tmp" "$BASE/lease"; }
clearlease() { rm -f "$BASE/lease" "$BASE/phase" "$BASE/ready" "$BASE/applied" "$BASE/permissions"; }
lock() {
    attempts=0
    while ! mkdir "$BASE/lock" 2>/dev/null; do
        owner=$(cat "$BASE/lock/pid" 2>/dev/null)
        if number "$owner" && ! kill -0 "$owner" 2>/dev/null; then rm -f "$BASE/lock/pid"; rmdir "$BASE/lock" 2>/dev/null; fi
        attempts=$((attempts+1)); [ "$attempts" -lt 30 ] || return 1; sleep 0.1
    done
    echo "$$" > "$BASE/lock/pid"; trap 'rm -f "$BASE/lock/pid"; rmdir "$BASE/lock" 2>/dev/null' EXIT
}
restorepermissions() {
    [ -f "$BASE/permissions" ] || return 0
    permissionfailed=0
    while read -r node oldmode tempmode; do
        if [ "$(stat -c %a "$node")" = "$tempmode" ]; then chmod "$oldmode" "$node" || permissionfailed=1; fi
    done < "$BASE/permissions"
    if [ "$permissionfailed" = 0 ]; then rm -f "$BASE/permissions"; else return 1; fi
}
writecheck() {
    nodepath=$1; value=$2
    [ "$(readnode "$nodepath")" != "$value" ] || return 0
    case "$nodepath" in "$GPU/"*)
        mode=$(stat -c %a "$nodepath") || return 1
        if [ $((8#$mode & 0200)) = 0 ]; then
            tempmode=$(printf '%o' "$((8#$mode | 0200))")
            printf '%s %s %s\n' "$nodepath" "$mode" "$tempmode" >> "$BASE/permissions" || return 1
            chmod "$tempmode" "$nodepath" || { restorepermissions; return 1; }
        fi;;
    esac
    written=0; printf '%s\n' "$value" > "$nodepath" && [ "$(readnode "$nodepath")" = "$value" ] && written=1
    restorepermissions || return 1
    [ "$written" = 1 ]
}
checkpoint() { [ "$(phase)" = trial ] && [ "$(now)" -lt "$deadline" ]; }
applypair() {
    p=$1; lo=$2; hi=$3
    if [ "$lo" -gt "$(readnode "$p/scaling_max_freq")" ]; then checkpoint && writecheck "$p/scaling_max_freq" "$hi" && checkpoint && writecheck "$p/scaling_min_freq" "$lo"
    else checkpoint && writecheck "$p/scaling_min_freq" "$lo" && checkpoint && writecheck "$p/scaling_max_freq" "$hi"; fi
}
applyraw() {
    set -- $target
    applypair "$CPU/policy0" "$1" "$2" && applypair "$CPU/policy4" "$3" "$4" && applypair "$CPU/policy7" "$5" "$6" || return 1
    # GED indices are descending: higher index means lower frequency. Clear the floor before lowering the ceiling.
    checkpoint && writecheck "$GPU/custom_boost_gpu_freq" "$7" && checkpoint && writecheck "$GPU/custom_upbound_gpu_freq" "$8" || return 1
    shift 8
    for g in $GROUPS; do checkpoint && printf '%s\n' "$(cpulist "$1")" > "$SETS/$g/cpus" || return 1; [ "$(mask "$(cat "$SETS/$g/cpus")")" = "$1" ] || return 1; shift; done
    [ "$(raw)" = "$target" ]
}
restorepair() {
    p=$1; lo=$2; hi=$3; amin=$4; amax=$5
    cmin=$(readnode "$p/scaling_min_freq"); cmax=$(readnode "$p/scaling_max_freq")
    if [ "$cmin" = "$amin" ] && [ "$lo" -gt "$cmax" ] && [ "$cmax" = "$amax" ]; then writecheck "$p/scaling_max_freq" "$hi" || return 1; cmax=$hi; fi
    if [ "$cmin" = "$amin" ] && [ "$lo" -le "$cmax" ]; then writecheck "$p/scaling_min_freq" "$lo" || return 1; cmin=$lo; fi
    if [ "$cmax" = "$amax" ] && [ "$hi" -ge "$cmin" ]; then writecheck "$p/scaling_max_freq" "$hi" || return 1; fi
    cmin=$(readnode "$p/scaling_min_freq"); cmax=$(readnode "$p/scaling_max_freq")
    { [ "$cmin" != "$amin" ] || [ "$cmin" = "$lo" ]; } && { [ "$cmax" != "$amax" ] || [ "$cmax" = "$hi" ]; }
}
restoreowned() {
    destination=$1; remaining=$target; set -- $destination; failed=0
    for id in 0 4 7; do
        dmin=$1; dmax=$2; shift 2
        amin=${remaining%% *}; remaining=${remaining#* }; amax=${remaining%% *}; remaining=${remaining#* }
        restorepair "$CPU/policy$id" "$dmin" "$dmax" "$amin" "$amax" || failed=1
    done
    for knob in custom_boost_gpu_freq custom_upbound_gpu_freq; do
        applied=${remaining%% *}; remaining=${remaining#* }
        if [ "$(readnode "$GPU/$knob")" = "$applied" ]; then writecheck "$GPU/$knob" "$1" || failed=1; fi
        shift
    done
    for g in $GROUPS; do
        applied=${remaining%% *}; remaining=${remaining#* }
        if [ "$(mask "$(cat "$SETS/$g/cpus")")" = "$applied" ]; then
            printf '%s\n' "$(cpulist "$1")" > "$SETS/$g/cpus" && [ "$(mask "$(cat "$SETS/$g/cpus")")" = "$1" ] || failed=1
        fi
        shift
    done
    [ "$failed" = 0 ]
}
rollback() {
    reason=$1; load || return 0; [ "$savedboot" = "$(boot)" ] || { clearlease; return 0; }
    setphase restoring
    if ! restoreowned "$previous"; then restorepermissions; setphase restore-failed; return 1; fi
    restorepermissions || { setphase restore-failed; return 1; }
    if [ "$previousphase" = active ]; then target=$previous; actor=$previousactor; deadline=0; save; setphase active; else clearlease; fi
    echo "$reason" > "$BASE/last"
}
validatefreq() {
    table=$(cat "$1/scaling_available_frequencies"); number "$2" && number "$3" && [ "$2" -le "$3" ] || return 1
    case " $table " in *" $2 "*) ;; *) return 1;; esac; case " $table " in *" $3 "*) ;; *) return 1;; esac
    [ "$(readnode "$1/scaling_governor")" = sugov_ext ]
}
maketarget() {
    [ "$#" = 12 ] || return 1
    for n in "$@"; do number "$n" || return 1; done
    validatefreq "$CPU/policy0" "$1" "$2" && validatefreq "$CPU/policy4" "$3" "$4" && validatefreq "$CPU/policy7" "$5" "$6" && [ "$7" -le "$8" ] || return 1
    lower=$(gpuindex "$7"); upper=$(gpuindex "$8"); number "$lower" && number "$upper" && [ "$lower" -ge "$upper" ] || return 1
    desired="$1 $2 $3 $4 $5 $6 $lower $upper"; shift 8
    for n in "$@"; do [ "$n" -gt 0 ] && [ "$n" -le 255 ] || return 1; desired="$desired $n"; done
}
validraw() {
    set -- $1; [ "$#" = 12 ] || return 1
    for n in "$@"; do number "$n" || return 1; done
    [ "$1" -gt 0 ] && [ "$1" -le "$2" ] && [ "$3" -gt 0 ] && [ "$3" -le "$4" ] && [ "$5" -gt 0 ] && [ "$5" -le "$6" ] && [ "$7" -ge "$8" ] || return 1
    [ -n "$(gpufreq "$7")" ] && [ -n "$(gpufreq "$8")" ] || return 1
    shift 8; for n in "$@"; do [ "$n" -gt 0 ] && [ "$n" -le 255 ] || return 1; done
}
arm() {
    nohup /system/bin/sh "$SELF" guard "$token" </dev/null > "$BASE/guard.log" 2>&1 &
    attempts=0; while [ "$(cat "$BASE/ready" 2>/dev/null)" != "$token" ] && [ "$attempts" -lt 30 ]; do sleep 0.05; attempts=$((attempts+1)); done
    [ "$(cat "$BASE/ready" 2>/dev/null)" = "$token" ]
}
trial() {
    label=$1; shift; platform; maketarget "$@" || fail '频率档位、调速器或核心列表无效'; lock || fail '已有操作正在执行'
    case "$label" in auto.*) [ "$(phase)" != trial ] || exit 0;; esac
    if load && [ "$savedboot" = "$(boot)" ]; then
        state=$(phase); [ "$state" = active ] || [ "$state" = trial ] || fail '请先处理恢复失败'
        if [ "$state" = active ]; then previous=$target; previousphase=active; previousactor=$actor; fi
    else original=$(raw); previous=$original; previousphase=none; previousactor=global; fi
    validraw "$original" && validraw "$previous" || fail '未能一致读取原值，未应用配置，请重试'
    # A replacement trial always returns to the last confirmed profile, not another unconfirmed draft.
    savedboot=$(boot); token=$(cat /proc/sys/kernel/random/uuid); deadline=$(($(now)+20)); target=$desired; actor=$label
    save || fail '无法保存回滚记录'; setphase trial; rm -f "$BASE/ready" "$BASE/applied"
    if ! arm; then rollback guard-start-failed; fail '回滚进程未就绪，未应用'; fi
    if applyraw; then echo "$token" > "$BASE/applied"; echo "token=$token"; else rollback apply-failed; fail '写入或回读未通过，已撤回本次调整'; fi
}
stopwatch() {
    rm -f "$BASE/auto_enabled"
    pid=$(cat "$BASE/watch.pid" 2>/dev/null)
    if number "$pid" && [ -r "/proc/$pid/cmdline" ]; then
        command=$(tr '\000' ' ' < "/proc/$pid/cmdline"); case "$command" in *"$SELF watch"*)
            kill "$pid" 2>/dev/null; tries=0
            while kill -0 "$pid" 2>/dev/null && [ "$tries" -lt 40 ]; do sleep 0.05; tries=$((tries+1)); done;; esac
    fi
    rm -f "$BASE/watch.ready"
}
watchalive() {
    [ -f "$BASE/watch.ready" ] && [ "$(cat "$BASE/watch.boot" 2>/dev/null)" = "$(boot)" ] || return 1
    watchpid=$(cat "$BASE/watch.pid" 2>/dev/null)
    number "$watchpid" && kill -0 "$watchpid" 2>/dev/null || return 1
    watchcommand=$(tr '\000' ' ' < "/proc/$watchpid/cmdline" 2>/dev/null)
    case "$watchcommand" in *"$SELF watch"*) return 0;; *) return 1;; esac
}
switchprofile() {
    pkg=$1; case "$pkg" in ''|*[!A-Za-z0-9_.]*) return 1;; esac
    [ "$(phase)" != trial ] || return 0
    [ -r "$BASE/rules" ] || return 1
    values=; fallback=
    while read -r name data; do [ "$name" != '*' ] || fallback=$data; if [ "$name" = "$pkg" ]; then values=$data; break; fi; done < "$BASE/rules"
    [ -n "$values" ] || values=$fallback; maketarget $values || return 1
    if load && [ "$savedboot" = "$(boot)" ] && [ "$(phase)" = active ] && [ "$target" = "$desired" ]; then return 0; fi
    result=$(sh "$SELF" trial "auto.$pkg" $values) || { echo "$result"; return 1; }
    t=${result#token=}; case "$t" in *[!a-f0-9-]*|'') return 0;; esac
    sh "$SELF" confirm "$t"
}
case "$1" in
probe)
    platform; if load && [ "$savedboot" != "$(boot)" ]; then clearlease; fi
    sample=$(raw); set -- $sample
    for id in 0 4 7; do p=$CPU/policy$id; printf 'policy%s=%s;%s;%s;%s\n' "$id" "$1" "$2" "$(readnode "$p/scaling_governor")" "$(cat "$p/scaling_available_frequencies")"; shift 2; done
    printf 'gpu=%s;%s;%s\n' "$(gpufreq "$1")" "$(gpufreq "$2")" "$(gputable)"; shift 2
    for g in $GROUPS; do echo "$g=$1"; shift; done
    state=none; left=0; token=; actor=global; match=1; target=
    if load; then state=$(phase); [ -n "$state" ] || state=none; left=$((deadline-$(now))); [ "$left" -gt 0 ] || left=0; [ "$(logical "$sample")" = "$(logical "$target")" ] || match=0; fi
    if [ -z "$target" ] && [ "$(sed -n 1p /data/adb/apptuner9400/lease 2>/dev/null)" = "$(boot)" ] && [ "$(cat /data/adb/apptuner9400/phase 2>/dev/null)" = active ]; then
        legacy=$(sed -n 8p /data/adb/apptuner9400/lease); echo "legacyTarget=$legacy"; state=legacy
        set -- $sample; savedgpu="$7 $8"; savedsystem=${10}; savedforeground=${11}
        set -- $legacy; oldrequest="$1 $2 $3 $4 $5 $6 $savedgpu $8 $savedsystem $savedforeground $7"
        [ "$(logical "$sample")" = "$(logical "$oldrequest")" ] || match=0
    fi
    echo "phase=$state"; echo "token=$token"; echo "remaining=$left"; echo "match=$match"; echo "actor=$actor"; echo "target=$target"; echo "last=$(cat "$BASE/last" 2>/dev/null)"
    echo "auto=$(test -f "$BASE/auto_enabled" && echo 1 || echo 0)"; echo "watch=$(watchalive && echo 1 || echo 0)"; echo "foregroundApp=$(cat "$BASE/foreground" 2>/dev/null)"; echo "watchError=$(head -n1 "$BASE/watch-error" 2>/dev/null)"
    # Translate the raw GED indices for the persisted intent separately from instantaneous readings.
    if [ -n "$target" ]; then set -- $target; echo "targetGpu=$(gpufreq "$7");$(gpufreq "$8")"; fi
    ;;
trial) shift; trial "$@";;
confirm)
    lock || fail '已有操作正在执行'; load || fail '试用已结束'
    [ "$2" = "$token" ] && [ "$(phase)" = trial ] && [ "$(now)" -lt "$deadline" ] && [ "$(cat "$BASE/applied" 2>/dev/null)" = "$token" ] || fail '试用已过期或未完整应用'
    setphase active; deadline=0; save; echo confirmed > "$BASE/last"; echo '配置已保留';;
rollback) lock || fail '已有操作正在执行'; rollback manual;;
restore)
    stopwatch; lock || fail '已有操作正在执行'; load || exit 0
    [ "$savedboot" = "$(boot)" ] || { clearlease; exit 0; }; setphase restoring
    if ! restoreowned "$original"; then restorepermissions; setphase restore-failed; fail '部分资源恢复失败，请重试'; fi
    restorepermissions || { setphase restore-failed; fail 'GPU 原权限未恢复，请重试'; }; clearlease; echo restored > "$BASE/last";;
switch) switchprofile "$2";;
start)
    platform; stopwatch; rm -f "$BASE/watch-error"; touch "$BASE/auto_enabled"
    nohup sh "$SELF" watch </dev/null > "$BASE/watch.log" 2>&1 &
    tries=0; while [ ! -f "$BASE/watch.ready" ] && [ "$tries" -lt 40 ]; do sleep 0.1; tries=$((tries+1)); done
    [ -f "$BASE/watch.ready" ] || { stopwatch; fail '前台事件监听启动失败'; };;
stop) stopwatch;;
watch)
    boot > "$BASE/watch.boot"
    echo "$$" > "$BASE/watch.pid"
    echo "$$" > /sys/fs/cgroup/cgroup.procs && echo "$$" > /dev/cpuset/tasks || exit 1
    fifo=$BASE/events.fifo; rm -f "$fifo"; mkfifo "$fifo" || exit 1
    logcat -b events -v raw -T 1 -s wm_set_resumed_activity > "$fifo" 2>/dev/null & reader=$!
    trap 'kill "$reader" 2>/dev/null; if [ "$(cat "$BASE/watch.pid" 2>/dev/null)" = "$$" ]; then rm -f "$fifo" "$BASE/watch.ready" "$BASE/watch.pid"; fi' EXIT
    trap 'exit 0' TERM INT
    foreground=$(dumpsys activity activities | awk '/topResumedActivity/ {for(i=1;i<=NF;i++) if($i ~ /\//){split($i,a,"/"); print a[1]; exit}}')
    if [ -n "$foreground" ]; then
        echo "$foreground" > "$BASE/foreground"
        if switchprofile "$foreground" > "$BASE/switch-output" 2>&1; then rm -f "$BASE/watch-error"; else cp "$BASE/switch-output" "$BASE/watch-error"; fi
    fi
    touch "$BASE/watch.ready"
    while read -r line; do
        [ -f "$BASE/auto_enabled" ] || break
        case "$line" in '['*,*/*) pkg=${line#*,}; pkg=${pkg%%/*};; *) continue;; esac
        case "$pkg" in ''|*[!A-Za-z0-9_.]*) continue;; esac
        [ "$pkg" != "$foreground" ] || continue
        foreground=$pkg; echo "$pkg" > "$BASE/foreground"
        if switchprofile "$pkg" > "$BASE/switch-output" 2>&1; then rm -f "$BASE/watch-error"; else cp "$BASE/switch-output" "$BASE/watch-error"; fi
    done < "$fifo"
    ;;
guard)
    expected=$2; load && [ "$token" = "$expected" ] || exit 0
    echo "$$" > /sys/fs/cgroup/cgroup.procs && echo "$$" > /dev/cpuset/tasks || exit 1
    grep -q '^0::/$' /proc/$$/cgroup || exit 1
    echo "$token" > "$BASE/ready"
    while load && [ "$token" = "$expected" ] && [ "$(phase)" = trial ]; do
        if [ "$(now)" -ge "$deadline" ]; then
            lock || { setphase restoring; }; load && [ "$token" = "$expected" ] && rollback expired; exit 0
        fi
        sleep 1
    done;;
*) fail '未知操作';;
esac
