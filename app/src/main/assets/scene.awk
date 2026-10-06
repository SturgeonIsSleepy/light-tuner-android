# Android Task/ActivityRecord dump. Unknown vendor window modes use the normal fallback.
function packageOf(line, words,n,i,value) {
    n=split(line,words," ")
    for(i=1;i<=n;i++)if(words[i]~/[A-Za-z][A-Za-z0-9_.]+\//){value=words[i];sub(/.*=/,"",value);sub(/\/.*/,"",value);gsub(/[^A-Za-z0-9_.]/,"",value);if(value~/\./)return value}
    return ""
}
/topResumedActivity/ {p=packageOf($0);if(p!="")top=p}
/Task\{|Task id|windowingMode=/ {
    if($0~/mode=|windowingMode=/){floating=($0~/mode=(freeform|pinned)|windowingMode=(freeform|pinned|5|2)([^0-9]|$)/);current=""}
    p=packageOf($0);if(p!="")current=p
}
/ActivityRecord\{/ {p=packageOf($0);if(p!="")current=p}
floating&&/mVisibleRequested=true|mVisible=true|visible=true|state=RESUMED/ {if(current!="")seen[current]=1}
END {if(top=="")top="global";print top;for(p in seen)print p}
