#!/system/bin/sh
BASE=/data/adb/apptuner9400-v3
SELF=$BASE/runtime.sh
CONTROL=$BASE/controller.sh
CORE=$BASE/lightcore
umask 077
number() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "${#1}" -le 7 ]; }
now() { read -r value rest < /proc/uptime; echo "${value%.*}"; }
boot() { cat /proc/sys/kernel/random/boot_id; }
alive() {
    pid=$(cat "$BASE/$1" 2>/dev/null)
    number "$pid" && kill -0 "$pid" 2>/dev/null || return 1
    command=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
    case "$command" in *"$2"*) return 0;; *) return 1;; esac
}
stopprocess() {
    if alive "$1" "$2"; then
        kill "$pid" 2>/dev/null
        count=0; while kill -0 "$pid" 2>/dev/null && [ "$count" -lt 30 ]; do sleep 0.1; count=$((count+1)); done
        kill -0 "$pid" 2>/dev/null && return 1
    fi
    return 0
}
cleantrace() {
    id=$(head -n1 "$BASE/record.id" 2>/dev/null)
    case "$id" in ''|*[!0-9a-f-]*) return 0;;esac
    [ "${#id}" = 36 ] || return 0
    rm -f "/data/misc/perfetto-traces/lighttuner-$id.pftrace" "$BASE/record.trace"
}
threadunlock() { rm -f "$BASE/thread-change.lock/pid";rmdir "$BASE/thread-change.lock"; }
threadlock() {
    attempt=0
    while ! mkdir "$BASE/thread-change.lock" 2>/dev/null; do
        attempt=$((attempt+1)); [ "$attempt" -lt 60 ] || { echo '线程配置正在处理，请稍后重试';return 1; }
        if [ "$attempt" -ge 3 ] && ! alive thread-change.lock/pid "$SELF";then threadunlock 2>/dev/null;fi
        sleep 0.05
    done
    echo "$$" > "$BASE/thread-change.lock/pid"
    trap 'threadunlock 2>/dev/null' EXIT
}
threadfinish() {
    rm -f "$BASE/thread-trial/previous" "$BASE/thread-trial/deadline" "$BASE/thread-trial/boot" || return 1
    rm -f "$BASE/thread-trial/token" && rmdir "$BASE/thread-trial"
}
threadrevert() {
    [ -d "$BASE/thread-trial" ] || return 0
    [ "$1" = "$(cat "$BASE/thread-trial/token")" ] || return 0
    [ "$1" != "$(cat "$BASE/thread-confirmed" 2>/dev/null)" ] || { threadfinish;return $?; }
    [ "$1" != "$(cat "$BASE/thread-reverted" 2>/dev/null)" ] || { threadfinish;return $?; }
    stopprocess threads.pid "$CORE threads" || return 1
    "$CORE" restore-threads "$BASE" || return 1
    cp "$BASE/thread-trial/previous" "$BASE/threads.rules.tmp" && mv "$BASE/threads.rules.tmp" "$BASE/threads.rules" || return 1
    printf '%s\n' "$1" > "$BASE/thread-reverted" || return 1
    threadfinish
}
threadheal() {
    [ -d "$BASE/thread-trial" ] || return 0
    sh "$SELF" thread-heal
}
scene() {
    [ "$(cat "$BASE/phase" 2>/dev/null)" != trial ] || return 0
    if ! mkdir "$BASE/scene.lock" 2>/dev/null; then
        alive scene.pid "$SELF" && return 0
        rmdir "$BASE/scene.lock" 2>/dev/null;mkdir "$BASE/scene.lock" 2>/dev/null || return 1
    fi
    echo "$$" > "$BASE/scene.pid"
    trap 'rmdir "$BASE/scene.lock" 2>/dev/null' EXIT
    dumpsys activity activities | awk -f "$BASE/scene.awk" > "$BASE/scene.apps.tmp"
    foreground=$(head -n1 "$BASE/scene.apps.tmp"); case "$foreground" in ''|*[!A-Za-z0-9_.]*) foreground=global;; esac
    echo "$foreground" > "$BASE/foreground"
    floating=$(sed '1d' "$BASE/scene.apps.tmp")
    if [ -n "$floating" ]; then
        echo floating > "$BASE/window_mode"; echo "$(($(now)+3))" > "$BASE/float-until"
        cat "$BASE/window-floor" > "$BASE/scene.values.tmp"
        for package in "$foreground" $floating; do
            awk -v p="$package" '$1==p { $1=""; sub(/^ /,"");print;found=1;exit } END{if(!found)exit 1}' "$BASE/rules" >> "$BASE/scene.values.tmp" || awk '$1=="*"{$1="";sub(/^ /,"");print;exit}' "$BASE/rules" >> "$BASE/scene.values.tmp"
        done
        awk 'function bits(a,b, result,power){result=0;power=1;while(a||b){if(a%2||b%2)result+=power;a=int(a/2);b=int(b/2);power*=2}return result} NF==12||NF==15{if(!seen++){for(i=1;i<=15;i++)v[i]=i<=NF?$i:-1}else{for(i=1;i<=8;i++)if(i%2){if($i<v[i])v[i]=$i}else if($i>v[i])v[i]=$i;for(i=9;i<=12;i++)v[i]=bits(v[i],$i);if(NF==15){if($13>=0&&(v[13]<0||$13<v[13]))v[13]=$13;for(i=14;i<=15;i++)if($i>v[i])v[i]=$i}}} END{if(seen){v[11]=bits(v[11],240);v[12]=bits(v[12],240);for(i=1;i<=15;i++)printf "%s%s",v[i],i==15?"\n":" "}}' "$BASE/scene.values.tmp" > "$BASE/scene.profile"
        values=$(cat "$BASE/scene.profile")
    elif [ "$(cat "$BASE/window_mode" 2>/dev/null)" = floating ] && [ "$(now)" -lt "$(cat "$BASE/float-until" 2>/dev/null)" ]; then
        values=$(cat "$BASE/scene.profile")
        if ! alive settle.pid "$SELF settle"; then nohup /system/bin/sh "$SELF" settle </dev/null > "$BASE/settle.log" 2>&1 & fi
    else
        echo normal > "$BASE/window_mode"
        rm -f "$BASE/scene.applied"
        sh "$CONTROL" switch "$foreground"; return $?
    fi
    [ -n "$values" ] || return 1
    sh "$CONTROL" window $values
}
ensure() {
    [ -f "$BASE/persistent-enabled" ] || return 0
    alive supervisor.pid "$SELF supervise" && return 0
    nohup /system/bin/sh "$SELF" supervise </dev/null > "$BASE/supervisor.log" 2>&1 &
}
resume() {
    [ -f "$BASE/persistent-enabled" ] || return 0
    threadheal || return 1
    current=$(boot)
    if [ "$(head -n1 "$BASE/lease" 2>/dev/null)" != "$current" ]; then
        sh "$CONTROL" probe >/dev/null || return 1
        values=$(cat "$BASE/resume-profile" 2>/dev/null)
        [ -n "$values" ] || values=$(awk '$1=="*"{$1="";sub(/^ /,"");print;exit}' "$BASE/rules")
        [ -n "$values" ] || return 1
        result=$(sh "$CONTROL" trial global $values) || { echo "$result"; return 1; }
        token=${result#token=}; case "$token" in ''|*[!a-f0-9-]*) return 1;; esac
        sh "$CONTROL" confirm "$token" || return 1
    fi
    case "$(cat "$BASE/phase" 2>/dev/null)" in restore-failed|restoring) return 1;; esac
    if [ -f "$BASE/want-auto" ] && ! alive watch.pid "$CONTROL watch"; then sh "$CONTROL" start || return 1; fi
    if [ -s "$BASE/threads.rules" ] && ! alive threads.pid "$CORE threads";then sh "$SELF" thread-worker || return 1;fi
}
case "$1" in
ensure) ensure;;
recover) ensure; resume;;
boot)
    [ -f "$BASE/persistent-enabled" ] || exit 0
    count=0; while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 120 ]; do sleep 1;count=$((count+1));done
    [ "$(getprop sys.boot_completed)" = 1 ] || exit 1
    ensure;;
supervise)
    if ! mkdir "$BASE/supervisor.lock" 2>/dev/null; then
        alive supervisor.pid "$SELF supervise" && exit 0
        rmdir "$BASE/supervisor.lock" 2>/dev/null; mkdir "$BASE/supervisor.lock" 2>/dev/null || exit 1
    fi
    echo "$$" > "$BASE/supervisor.pid"
    echo "$$" > /sys/fs/cgroup/cgroup.procs || exit 1
    echo "$$" > /dev/cpuset/tasks || exit 1
    trap 'rmdir "$BASE/supervisor.lock" 2>/dev/null' EXIT
    trap 'exit 0' TERM INT
    delay=5
    while [ -f "$BASE/persistent-enabled" ]; do
        if resume > "$BASE/recovery-last" 2>&1; then delay=5;else delay=$((delay*2));[ "$delay" -le 60 ] || delay=60;fi
        sleep "$delay"
    done;;
disable)
    sh "$SELF" thread-revert "$(cat "$BASE/thread-trial/token" 2>/dev/null)" || exit 1
    rm -f "$BASE/persistent-enabled" "$BASE/want-auto" "$BASE/scene.applied" "$BASE/float-until"
    stopprocess supervisor.pid "$SELF supervise" || exit 1
    stopprocess threads.pid "$CORE threads" || exit 1
    "$CORE" restore-threads "$BASE";;
scene) scene;;
settle)
    echo "$$" > "$BASE/settle.pid"
    echo "$$" > /sys/fs/cgroup/cgroup.procs || exit 1
    echo "$$" > /dev/cpuset/tasks || exit 1
    sleep 3
    [ -f "$BASE/persistent-enabled" ] && [ -f "$BASE/want-auto" ] && scene;;
record-live)
    sh "$SELF" record-state >/dev/null || exit 1
    cat "$BASE/record.live.tsv" 2>/dev/null;head -n1 "$BASE/record.state" 2>/dev/null;;
record-start)
    number "$2" && [ "$2" -ge 5 ] && [ "$2" -le 600 ] || exit 1
    case "$3" in '*'|*[!A-Za-z0-9_.]*) [ "$3" = '*' ] || exit 1;; esac
    alive record.pid "$CORE record" && { echo '已有录制正在进行';exit 1; }
    stopprocess record.perfetto.pid "perfetto --txt -c $BASE/record.perfetto.cfg" || { echo '上一场帧录制尚未退出';exit 1; }
    cleantrace
    rm -f "$BASE/record.threads.tsv" "$BASE/record.frequencies.csv" "$BASE/record.threads.tmp" "$BASE/record.timeline.csv" "$BASE/record.timeline-capped" "$BASE/record.metrics.csv" "$BASE/record.frames.csv" "$BASE/record.layers.tsv" "$BASE/record.frame-bins.tsv" "$BASE/record.frames.state" "$BASE/record.live.tsv"
    cp "$BASE/record.context" "$BASE/record.meta.json" || exit 1
    id=$(cat /proc/sys/kernel/random/uuid)
    echo "$id" > "$BASE/record.id"; boot > "$BASE/record.boot"
    printf 'starting\t0\t0\t0\n' > "$BASE/record.state"
    nohup "$CORE" record "$BASE" "$2" "$3" </dev/null > "$BASE/record.log" 2>&1 &
    attempts=0;while ! alive record.pid "$CORE record" && [ "$attempts" -lt 40 ];do sleep 0.05;attempts=$((attempts+1));done
    alive record.pid "$CORE record" || { echo '录制进程未启动'; exit 1; };;
record-stop) stopprocess record.pid "$CORE record";;
record-state)
    [ -f "$BASE/record.state" ] || { printf 'none\t0\t0\t0\n';exit 0; }
    state=$(head -n1 "$BASE/record.state"); phase=${state%%$(printf '\t')*}
    case "$phase" in recording|starting|processing)
        if ! alive record.pid "$CORE record" || [ "$(cat "$BASE/record.boot")" != "$(boot)" ]; then
            stopprocess record.perfetto.pid "perfetto --txt -c $BASE/record.perfetto.cfg" || exit 1
            cleantrace
            printf '%s\n' "$state" | sed 's/^[^[:space:]]*/interrupted/' > "$BASE/record.state"
        fi;;
    esac
    cat "$BASE/record.state";;
thread-install)
    threadlock || exit 1
    [ ! -d "$BASE/thread-trial" ] || { echo '请先确认或撤回线程试用';exit 1; }
    cp "$BASE/threads.staged" "$BASE/threads.rules.tmp" && mv "$BASE/threads.rules.tmp" "$BASE/threads.rules" || exit 1
    threadunlock;trap - EXIT
    ensure;resume;;
thread-worker)
    threadlock || exit 1
    [ -f "$BASE/persistent-enabled" ] && [ -s "$BASE/threads.rules" ] || exit 0
    if ! alive threads.pid "$CORE threads";then
        nohup "$CORE" threads "$BASE" </dev/null > "$BASE/threads.log" 2>&1 &
        attempt=0;while ! alive threads.pid "$CORE threads" && [ "$attempt" -lt 30 ];do sleep 0.05;attempt=$((attempt+1));done
        alive threads.pid "$CORE threads" || exit 1
    fi;;
thread-trial)
    threadlock || exit 1
    [ -f "$BASE/persistent-enabled" ] && [ "$(cat "$BASE/phase")" = active ] || { echo '先确认全局接管配置，再试用线程策略';exit 1; }
    [ ! -d "$BASE/thread-trial" ] || { echo '已有线程配置试用，请先确认或撤回';exit 1; }
    [ -f "$BASE/threads.staged" ] || exit 1
    mkdir -p "$BASE/thread-next" || exit 1
    token=$(cat /proc/sys/kernel/random/uuid)
    if [ -f "$BASE/threads.rules" ]; then cp "$BASE/threads.rules" "$BASE/thread-next/previous" || exit 1;else : > "$BASE/thread-next/previous" || exit 1;fi
    printf '%s\n' "$token" > "$BASE/thread-next/token" || exit 1
    boot > "$BASE/thread-next/boot" || exit 1
    stopprocess threads.pid "$CORE threads" || exit 1
    "$CORE" restore-threads "$BASE" || exit 1
    echo "$(($(now)+20))" > "$BASE/thread-next/deadline" || exit 1
    mv "$BASE/thread-next" "$BASE/thread-trial" || exit 1
    nohup /system/bin/sh "$SELF" thread-guard "$token" </dev/null > "$BASE/thread-guard.log" 2>&1 &
    attempt=0;while ! alive thread-guard.pid "$SELF thread-guard $token" && [ "$attempt" -lt 30 ];do sleep 0.05;attempt=$((attempt+1));done
    if ! alive thread-guard.pid "$SELF thread-guard $token";then threadrevert "$token";echo '线程回滚守护未启动';exit 1;fi
    cp "$BASE/threads.staged" "$BASE/threads.rules.tmp" && mv "$BASE/threads.rules.tmp" "$BASE/threads.rules" || { threadrevert "$token";exit 1; }
    threadunlock;trap - EXIT
    ensure;resume;;
thread-confirm)
    threadlock || exit 1
    [ -d "$BASE/thread-trial" ] && [ "$2" = "$(cat "$BASE/thread-trial/token")" ] || { echo '线程试用已结束';exit 1; }
    [ "$(now)" -lt "$(cat "$BASE/thread-trial/deadline")" ] && [ "$(boot)" = "$(cat "$BASE/thread-trial/boot")" ] || { threadrevert "$2";echo '线程试用已到期';exit 1; }
    printf '%s\n' "$2" > "$BASE/thread-confirmed" || exit 1
    threadfinish;;
thread-revert)
    threadlock || exit 1
    threadrevert "$2" || exit 1
    threadunlock;trap - EXIT
    ensure;resume;;
thread-heal)
    threadlock || exit 1
    [ -d "$BASE/thread-trial" ] || exit 0
    token=$(cat "$BASE/thread-trial/token" 2>/dev/null)
    if [ -z "$token" ] && [ ! -f "$BASE/thread-trial/previous" ];then rmdir "$BASE/thread-trial";exit $?;fi
    if [ "$token" = "$(cat "$BASE/thread-confirmed" 2>/dev/null)" ] || [ "$token" = "$(cat "$BASE/thread-reverted" 2>/dev/null)" ];then threadfinish;exit $?;fi
    deadline=$(cat "$BASE/thread-trial/deadline" 2>/dev/null)
    if [ "$(cat "$BASE/thread-trial/boot" 2>/dev/null)" != "$(boot)" ] || ! number "$deadline" || [ "$(now)" -ge "$deadline" ] || ! alive thread-guard.pid "$SELF thread-guard $token";then threadrevert "$token";fi;;
thread-state)
    threadheal || exit 1
    if [ -d "$BASE/thread-trial" ];then
        echo threadToken="$(cat "$BASE/thread-trial/token")"
        echo threadRemaining="$(( $(cat "$BASE/thread-trial/deadline")-$(now) ))"
    fi
    echo threadConfirmed="$(cat "$BASE/thread-confirmed" 2>/dev/null)";;
thread-guard)
    echo "$$" > /sys/fs/cgroup/cgroup.procs || exit 1
    echo "$$" > /dev/cpuset/tasks || exit 1
    echo "$$" > "$BASE/thread-guard.pid"
    while [ "$2" = "$(cat "$BASE/thread-trial/token" 2>/dev/null)" ];do
        [ "$(now)" -lt "$(cat "$BASE/thread-trial/deadline")" ] || { sh "$SELF" thread-revert "$2";exit $?; }
        sleep 1
    done;;
*) exit 1;;
esac
