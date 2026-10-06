package io.github.sturgeonissleepy.lighttuner;

import org.json.*;
import java.util.*;

/** Complete CPU, GPU and four-cpuset profile. Frequencies are integer kHz. */
public final class PlatformProfile {
    public static final int[] POLICIES={0,4,7};
    public static final String[] GROUPS={"background","systemBackground","foreground","topApp"};
    public final int[] min,max,masks;
    public final int gpuMin,gpuMax;
    public final int upRateUs,downRateUs,topAppMin;
    public PlatformProfile(int[] min,int[] max,int gpuMin,int gpuMax,int[] masks) throws JSONException {
        this(min,max,gpuMin,gpuMax,masks,-1,-1,-1);
    }
    public PlatformProfile(int[] min,int[] max,int gpuMin,int gpuMax,int[] masks,int up,int down,int boost) throws JSONException {
        require(min.length==3 && max.length==3 && masks.length==4,"必须配置三簇 CPU 和四类进程组");
        this.min=min.clone(); this.max=max.clone(); this.masks=masks.clone(); this.gpuMin=gpuMin; this.gpuMax=gpuMax;
        require(up>=-1&&up<=1000000&&down>=-1&&down<=1000000&&boost>=-1&&boost<=80,"调度响应参数超出范围");upRateUs=up;downRateUs=down;topAppMin=boost;
        for(int i=0;i<3;i++) require(min[i]>0 && min[i]<=max[i] && max[i]<=4000000,"CPU 频率范围无效");
        require(gpuMin>0 && gpuMin<=gpuMax && gpuMax<=4000000,"GPU 频率范围无效");
        for(int m:masks) require(m>0 && m<=255,"每类进程至少选择 CPU0–7 中的一个核心");
    }
    public String arguments() {
        return min[0]+" "+max[0]+" "+min[1]+" "+max[1]+" "+min[2]+" "+max[2]+" "+gpuMin+" "+gpuMax+" "+masks[0]+" "+masks[1]+" "+masks[2]+" "+masks[3]+" "+upRateUs+" "+downRateUs+" "+topAppMin;
    }
    public JSONObject json() throws JSONException {
        JSONArray clusters=new JSONArray(); JSONObject sets=new JSONObject();
        for(int i=0;i<3;i++) clusters.put(new JSONObject().put("policy","policy"+POLICIES[i]).put("minKHz",min[i]).put("maxKHz",max[i]));
        for(int i=0;i<4;i++) sets.put(GROUPS[i],array(masks[i]));
        JSONObject out=new JSONObject().put("clusters",clusters).put("gpu",new JSONObject().put("minKHz",gpuMin).put("maxKHz",gpuMax)).put("cpuSets",sets);if(upRateUs>=0||downRateUs>=0||topAppMin>=0)out.put("response",new JSONObject().put("upRateUs",upRateUs).put("downRateUs",downRateUs).put("topAppMinPercent",topAppMin));return out;
    }
    public static PlatformProfile parse(JSONObject o) throws JSONException {
        if(o.has("response"))keys(o,"clusters","gpu","cpuSets","response");else keys(o,"clusters","gpu","cpuSets");JSONObject sets=o.getJSONObject("cpuSets"); keys(sets,GROUPS);
        JSONArray clusters=o.getJSONArray("clusters"); require(clusters.length()==3,"必须配置三簇 CPU");
        int[] lower=new int[3],upper=new int[3],masks=new int[4]; Set<Integer> seen=new HashSet<>();
        for(int j=0;j<3;j++) {
            JSONObject c=clusters.getJSONObject(j); keys(c,"policy","minKHz","maxKHz");
            int i=List.of("policy0","policy4","policy7").indexOf(c.get("policy")); require(i>=0 && seen.add(i),"CPU 簇未知或重复");
            lower[i]=integer(c,"minKHz"); upper[i]=integer(c,"maxKHz");
        }
        for(int i=0;i<4;i++) masks[i]=mask(sets.getJSONArray(GROUPS[i]));
        JSONObject gpu=o.getJSONObject("gpu"); keys(gpu,"minKHz","maxKHz");
        int up=-1,down=-1,boost=-1;if(o.has("response")){JSONObject response=o.getJSONObject("response");keys(response,"upRateUs","downRateUs","topAppMinPercent");up=responseInt(response,"upRateUs");down=responseInt(response,"downRateUs");boost=responseInt(response,"topAppMinPercent");}return new PlatformProfile(lower,upper,integer(gpu,"minKHz"),integer(gpu,"maxKHz"),masks,up,down,boost);
    }
    private static int responseInt(JSONObject o,String key)throws JSONException {Object value=o.get(key);require(value instanceof Integer||value instanceof Long,"响应参数必须为整数");long n=((Number)value).longValue();require(n>=-1&&n<=1000000,"响应参数超出范围");return (int)n;}
    public static JSONObject object(String text) throws JSONException {
        JSONTokener t=new JSONTokener(text); Object v=t.nextValue(); require(v instanceof JSONObject && t.nextClean()==0,"文件必须是一个 JSON 对象"); return (JSONObject)v;
    }
    private static JSONArray array(int mask) { JSONArray a=new JSONArray(); for(int i=0;i<8;i++) if((mask&(1<<i))!=0) a.put(i); return a; }
    private static int mask(JSONArray a) throws JSONException {
        require(a.length()>0 && a.length()<=8,"核心列表为空或过长"); int result=0;
        for(int i=0;i<a.length();i++) { Object v=a.get(i); require(v instanceof Integer || v instanceof Long,"核心编号必须是整数"); long n=((Number)v).longValue(); require(n>=0 && n<=7 && (result&(1<<(int)n))==0,"核心编号未知或重复"); result|=1<<(int)n; } return result;
    }
    public static int integer(JSONObject o,String key) throws JSONException {
        Object v=o.get(key); require(v instanceof Integer || v instanceof Long,key+" 必须是整数"); long n=((Number)v).longValue(); require(n>=0 && n<=4000000,key+" 超出范围"); return (int)n;
    }
    public static void keys(JSONObject o,String... names) throws JSONException {
        Set<String> valid=new HashSet<>(Arrays.asList(names)); require(o.length()==valid.size(),"配置字段不完整或存在未知字段");
        for(Iterator<String> it=o.keys();it.hasNext();) require(valid.contains(it.next()),"不接受未知字段");
    }
    public static void require(boolean ok,String message) throws JSONException { if(!ok) throw new JSONException(message); }
}
