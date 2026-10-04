package cc.ccwu.apptuner;

import org.json.*;
import java.util.*;

/** Three editable presets, a global fallback, and foreground application overrides. */
public final class PolicyBook {
    public static final String[] IDS={"powersave","balanced","performance"};
    public static final String[] NAMES={"省电","均衡","性能"};
    public final PlatformProfile[] presets;
    public String global="balanced";
    public final LinkedHashMap<String,Rule> apps=new LinkedHashMap<>();
    public PolicyBook(PlatformProfile[] presets) { this.presets=presets.clone(); }
    public static final class Rule {
        public boolean enabled=true; public String preset="balanced"; public PlatformProfile custom;
        public PlatformProfile resolve(PolicyBook b) { return custom==null ? b.profile(preset) : custom; }
    }
    public PlatformProfile profile(String id) { return presets[index(id)]; }
    public static int index(String id) { return Arrays.asList(IDS).indexOf(id); }
    public static boolean packageAllowed(String pkg) { return pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") && !List.of("cc.ccwu.apptuner","com.android.systemui","me.weishu.kernelsu").contains(pkg); }
    public PlatformProfile forApp(String pkg) { Rule rule=apps.get(pkg); return rule!=null && rule.enabled ? rule.resolve(this) : profile(global); }
    public String rootRules() {
        StringBuilder out=new StringBuilder("* "+profile(global).arguments()+"\n");
        for(Map.Entry<String,Rule> e:apps.entrySet()) if(e.getValue().enabled) out.append(e.getKey()).append(' ').append(e.getValue().resolve(this).arguments()).append('\n'); return out.toString();
    }
    public JSONObject json() throws JSONException {
        JSONObject p=new JSONObject(); for(int i=0;i<3;i++) p.put(IDS[i],presets[i].json()); JSONArray rules=new JSONArray();
        for(Map.Entry<String,Rule> e:apps.entrySet()) { Rule r=e.getValue(); JSONObject o=new JSONObject().put("packageName",e.getKey()).put("enabled",r.enabled).put("preset",r.preset); if(r.custom!=null) o.put("custom",r.custom.json()); rules.put(o); }
        return new JSONObject().put("schemaVersion",3).put("platform","mt6991").put("globalPreset",global).put("presets",p).put("applications",rules);
    }
    public static PolicyBook parse(String text) throws JSONException {
        JSONObject o=PlatformProfile.object(text); PlatformProfile.keys(o,"schemaVersion","platform","globalPreset","presets","applications");
        PlatformProfile.require(PlatformProfile.integer(o,"schemaVersion")==3 && "mt6991".equals(o.get("platform")),"只接受 MT6991 v3 配置集");
        JSONObject p=o.getJSONObject("presets"); PlatformProfile.keys(p,IDS); PlatformProfile[] values=new PlatformProfile[3]; for(int i=0;i<3;i++) values[i]=PlatformProfile.parse(p.getJSONObject(IDS[i]));
        PolicyBook b=new PolicyBook(values); b.global=o.getString("globalPreset"); PlatformProfile.require(index(b.global)>=0,"全局预设不存在");
        JSONArray rules=o.getJSONArray("applications"); PlatformProfile.require(rules.length()<=200,"最多 200 条分应用策略");
        for(int i=0;i<rules.length();i++) {
            JSONObject r=rules.getJSONObject(i); if(r.has("custom")) PlatformProfile.keys(r,"packageName","enabled","preset","custom"); else PlatformProfile.keys(r,"packageName","enabled","preset");
            String pkg=r.getString("packageName"); PlatformProfile.require(packageAllowed(pkg) && !b.apps.containsKey(pkg),"应用包名无效、受保护或重复");
            PlatformProfile.require(r.get("enabled") instanceof Boolean,"enabled 必须是布尔值"); Rule rule=new Rule(); rule.enabled=r.getBoolean("enabled"); rule.preset=r.getString("preset"); PlatformProfile.require(index(rule.preset)>=0,"应用预设不存在");
            if(r.has("custom")) rule.custom=PlatformProfile.parse(r.getJSONObject("custom")); b.apps.put(pkg,rule);
        }
        return b;
    }
}