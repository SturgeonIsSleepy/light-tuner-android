package io.github.sturgeonissleepy.lighttuner;

import org.json.*;
import java.util.*;

/** A thread name is scoped to an application version, never to a persistent TID. */
public final class ThreadRules {
    public final List<Rule> entries=new ArrayList<>();
    public static final class Rule {
        public String packageName,versionCode,threadName;
        public int cpuMask;
        public boolean enabled;
        public Rule(String pkg,String version,String name,int mask,boolean enabled) throws JSONException {
            PlatformProfile.require(PolicyBook.packageAllowed(pkg),"应用不允许配置线程");
            PlatformProfile.require(version.matches("[0-9]{1,19}")&&Long.parseLong(version)>=0,"应用版本无效");
            PlatformProfile.require(!name.isEmpty()&&name.length()<=64&&!name.matches("(?s).*[\\p{Cntrl}].*"),"线程名称包含无效字符");
            PlatformProfile.require(mask>0&&mask<=255,"至少允许一个 CPU 核心");
            packageName=pkg;versionCode=version;threadName=name;cpuMask=mask;this.enabled=enabled;
        }
        public JSONObject json() throws JSONException {return new JSONObject().put("packageName",packageName).put("versionCode",versionCode).put("threadName",threadName).put("cpuMask",cpuMask).put("enabled",enabled);}
        public static Rule parse(JSONObject o) throws JSONException {PlatformProfile.keys(o,"packageName","versionCode","threadName","cpuMask","enabled");PlatformProfile.require(o.get("enabled") instanceof Boolean,"启用状态无效");return new Rule(o.getString("packageName"),o.getString("versionCode"),o.getString("threadName"),PlatformProfile.integer(o,"cpuMask"),o.getBoolean("enabled"));}
    }
    public void put(Rule rule) throws JSONException {entries.removeIf(r->r.packageName.equals(rule.packageName)&&r.versionCode.equals(rule.versionCode)&&r.threadName.equals(rule.threadName));PlatformProfile.require(entries.size()<256,"最多保存 256 条线程规则");entries.add(rule);}
    public String json() throws JSONException {JSONArray array=new JSONArray();for(Rule r:entries)array.put(r.json());return new JSONObject().put("schemaVersion",1).put("threads",array).toString();}
    public static ThreadRules parse(String source) throws JSONException {ThreadRules out=new ThreadRules();if(source.isEmpty())return out;JSONObject o=PlatformProfile.object(source);PlatformProfile.keys(o,"schemaVersion","threads");PlatformProfile.require(PlatformProfile.integer(o,"schemaVersion")==1,"线程格式版本无效");JSONArray list=o.getJSONArray("threads");PlatformProfile.require(list.length()<=256,"线程规则过多");for(int i=0;i<list.length();i++)out.put(Rule.parse(list.getJSONObject(i)));return out;}
    public String compiled(){StringBuilder out=new StringBuilder();for(Rule r:entries)if(r.enabled)out.append(r.packageName).append('\t').append(r.versionCode).append('\t').append(r.threadName).append('\t').append(r.cpuMask).append('\n');return out.toString();}
}
