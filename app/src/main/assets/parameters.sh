#!/system/bin/sh
# Optional governor response and top-app boost, journaled with the global trial token.
BASE=/data/adb/apptuner9400-v3
umask 077
boot() { cat /proc/sys/kernel/random/boot_id; }
nodes() { for id in 0 4 7;do echo "/sys/devices/system/cpu/cpufreq/policy$id/sugov_ext/up_rate_limit_us";echo "/sys/devices/system/cpu/cpufreq/policy$id/sugov_ext/down_rate_limit_us";done;echo /dev/cpuctl/top-app/cpu.uclamp.min; }
value() { if [ -r "$1" ];then case "$1" in */cpu.uclamp.min) awk 'NR==1{if($1~/^[0-9]+(\.[0-9]+)?$/&&$1<=100)printf "%.2f\n",$1;else print "-";exit}' "$1";;*) head -n1 "$1";;esac;else echo '-';fi; }
write() { [ "$(value "$1")" = "$2" ] || { printf '%s\n' "$2" > "$1" && [ "$(value "$1")" = "$2" ]; }; }
valid() { [ "$1" = -1 ] && return 0;case "$1" in ''|*[!0-9]*) return 1;;esac;[ "${#1}" -le 7 ] && [ "$1" -le "$2" ]; }
checkpoint() { [ "$(cat "$BASE/phase" 2>/dev/null)" = trial ] && [ "$(sed -n 2p "$BASE/lease")" = "$1" ] || return 1;read -r time rest < /proc/uptime;[ "${time%.*}" -lt "$(sed -n 3p "$BASE/lease")" ]; }
ownedrestore() { source=$1;column=$2;[ -f "$source" ] || return 0;[ "$(head -n1 "$source")" = "$(boot)" ] || return 0;sed '1d' "$source" | while read -r node original previous wanted own;do [ "$own" = 1 ] || continue;[ "$wanted" != '-' ] || continue;if [ "$(value "$node")" = "$wanted" ];then if [ "$column" = previous ];then write "$node" "$previous" || exit 1;else write "$node" "$original" || exit 1;fi;fi;done; }
case "$1" in
prepare)
    token=$2;up=$3;down=$4;clamp=$5
    valid "$up" 1000000 && valid "$down" 1000000 && valid "$clamp" 80 || exit 1
    if [ -f "$BASE/parameters.lease" ] && [ "$(head -n1 "$BASE/parameters.lease")" != "$(boot)" ];then rm -f "$BASE/parameters.lease" "$BASE/parameters.flags";fi
    boot > "$BASE/parameters.next"
    nodes | while read -r node;do
        current=$(value "$node");old=$(awk -v path="$node" '$1==path{print $2;exit}' "$BASE/parameters.lease" 2>/dev/null);oldApplied=$(awk -v path="$node" '$1==path{print $4;exit}' "$BASE/parameters.lease" 2>/dev/null)
        original=$old;[ -n "$original" ] || original=$current
        case "$node" in */up_rate_limit_us) requested=$up;;*/down_rate_limit_us) requested=$down;;*) requested=$clamp;;esac
        wanted=$current;own=0
        if [ "$requested" != -1 ];then [ "$current" != '-' ] || { echo '设备不支持所选调度响应参数' >&2;exit 1; };wanted=$requested;own=1;case "$node" in */cpu.uclamp.min) wanted=$(awk -v n="$requested" 'BEGIN{printf "%.2f",n}');;esac
        elif [ -n "$oldApplied" ] && [ "$current" = "$oldApplied" ];then wanted=$original;own=1;fi
        printf '%s\t%s\t%s\t%s\t%s\n' "$node" "$original" "$current" "$wanted" "$own" >> "$BASE/parameters.next" || exit 1
    done || exit 1
    printf '%s %s %s\n' "$up" "$down" "$clamp" > "$BASE/parameters.next.flags"
    printf '%s\n' "$token" > "$BASE/parameters.token"
    mv "$BASE/parameters.next.flags" "$BASE/parameters.trial.flags" && mv "$BASE/parameters.next" "$BASE/parameters.trial";;
apply)
    [ "$2" = "$(cat "$BASE/parameters.token")" ] || exit 1
    sed '1d' "$BASE/parameters.trial" | while read -r node original previous wanted own;do [ "$own" = 1 ] || continue;checkpoint "$2" && write "$node" "$wanted" || exit 1;done;;
confirm)
    [ "$2" = "$(cat "$BASE/parameters.token" 2>/dev/null)" ] || exit 0
    read -r up down clamp < "$BASE/parameters.trial.flags"
    { boot;sed '1d' "$BASE/parameters.trial" | while read -r node original previous wanted own;do case "$node" in */up_rate_limit_us) requested=$up;;*/down_rate_limit_us) requested=$down;;*) requested=$clamp;;esac;[ "$requested" != -1 ] || own=0;printf '%s\t%s\t%s\t%s\t%s\n' "$node" "$original" "$previous" "$wanted" "$own";done; } > "$BASE/parameters.lease.new" && mv "$BASE/parameters.lease.new" "$BASE/parameters.lease" && cp "$BASE/parameters.trial.flags" "$BASE/parameters.flags" || exit 1
    rm -f "$BASE/parameters.trial" "$BASE/parameters.trial.flags" "$BASE/parameters.token";;
rollback)
    [ "$2" = "$(cat "$BASE/parameters.token" 2>/dev/null)" ] || exit 0
    ownedrestore "$BASE/parameters.trial" previous || exit 1
    rm -f "$BASE/parameters.trial" "$BASE/parameters.trial.flags" "$BASE/parameters.token";;
heal)
    if [ -f "$BASE/parameters.trial" ] && [ "$(head -n1 "$BASE/parameters.trial")" != "$(boot)" ];then rm -f "$BASE/parameters.trial" "$BASE/parameters.trial.flags" "$BASE/parameters.token";fi
    if [ -f "$BASE/parameters.lease" ] && [ "$(head -n1 "$BASE/parameters.lease")" != "$(boot)" ];then rm -f "$BASE/parameters.lease" "$BASE/parameters.flags";fi
    token=$(cat "$BASE/parameters.token" 2>/dev/null)
    if [ -n "$token" ] && [ "$(head -n1 "$BASE/lease")" = "$(boot)" ] && [ "$(cat "$BASE/phase" 2>/dev/null)" = active ] && [ "$token" = "$(sed -n 2p "$BASE/lease")" ];then sh "$0" confirm "$token";fi;;
restore)
    sh "$0" heal || exit 1
    ownedrestore "$BASE/parameters.trial" previous && ownedrestore "$BASE/parameters.lease" original || exit 1
    rm -f "$BASE/parameters.trial" "$BASE/parameters.trial.flags" "$BASE/parameters.token" "$BASE/parameters.lease" "$BASE/parameters.flags";;
probe)
    printf 'tuningActual=';sep=;nodes | while read -r node;do printf '%s%s' "$sep" "$(value "$node")";sep=';';done;echo
    if [ -f "$BASE/parameters.trial" ] && [ "$(cat "$BASE/parameters.token")" = "$(sed -n 2p "$BASE/lease")" ];then source=$BASE/parameters.trial;flags=$BASE/parameters.trial.flags;else source=$BASE/parameters.lease;flags=$BASE/parameters.flags;fi
    printf 'tuningTarget=';if [ -f "$flags" ];then cat "$flags";else echo '-1 -1 -1';fi
    if [ -f "$source" ];then sed '1d' "$source" | while read -r node original previous wanted own;do [ "$own" = 1 ] || continue;[ "$(value "$node")" = "$wanted" ] || exit 1;done;if [ "$?" != 0 ];then echo tuningMatch=0;else echo tuningMatch=1;fi;else echo tuningMatch=1;fi;;
*) exit 1;;
esac
