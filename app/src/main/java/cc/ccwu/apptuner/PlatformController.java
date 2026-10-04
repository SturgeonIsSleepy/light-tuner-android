package cc.ccwu.apptuner;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** An on-demand root session; optional application switching uses blocking logcat events. */
public final class PlatformController implements AutoCloseable {
    private static final String BASE="/data/adb/apptuner9400-v3", HELPER=BASE+"/controller.sh";
    private final RootShell shell;
    private final Context context;
    public PlatformController(Context context) throws Exception {
        this.context=context;
        File asset=new File(context.getFilesDir(),"mt6991.sh");
        try(InputStream in=context.getAssets().open("mt6991.sh"); OutputStream out=new FileOutputStream(asset)) { byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) out.write(b,0,n); }
        shell=new RootShell();
        try { shell.exec("mkdir -p "+BASE+" && chmod 700 "+BASE+" && cp '"+asset.getAbsolutePath()+"' "+HELPER+" && chmod 700 "+HELPER); }
        catch(Exception e) { shell.close(); throw e; }
    }
    public Snapshot probe() throws Exception { return new Snapshot(shell.exec("sh "+HELPER+" probe")); }
    public Snapshot apply(PlatformProfile profile) throws Exception {
        Snapshot s=probe(); s.validate(profile);
        shell.exec("sh "+HELPER+" trial global "+profile.arguments()); return probe();
    }
    public Snapshot confirm(String token) throws Exception {
        if(!token.matches("[0-9a-f-]{36}")) throw new IOException("试用编号无效");
        shell.exec("sh "+HELPER+" confirm "+token); return probe();
    }
    public void installBook(PolicyBook book) throws Exception {
        Snapshot s=probe(); for(PlatformProfile p:book.presets) s.validate(p); for(PolicyBook.Rule r:book.apps.values()) if(r.custom!=null) s.validate(r.custom);
        File rules=new File(context.getFilesDir(),"rules.txt"); try(OutputStream out=new FileOutputStream(rules)) { out.write(book.rootRules().getBytes(StandardCharsets.UTF_8)); }
        shell.exec("cp '"+rules.getAbsolutePath()+"' "+BASE+"/rules.tmp && chmod 600 "+BASE+"/rules.tmp && mv "+BASE+"/rules.tmp "+BASE+"/rules");
    }
    public Snapshot startAuto(PolicyBook book) throws Exception { installBook(book); shell.exec("sh "+HELPER+" start"); return probe(); }
    public Snapshot stopAuto(PolicyBook book) throws Exception { shell.exec("sh "+HELPER+" stop"); installBook(book); shell.exec("sh "+HELPER+" switch global"); return probe(); }
    public Snapshot reload(PolicyBook book) throws Exception {
        installBook(book); Snapshot s=probe(); if(s.auto) { String pkg=s.foreground; if(!pkg.matches("[A-Za-z0-9_.]+")) pkg="global"; shell.exec("sh "+HELPER+" switch "+pkg); } return probe();
    }
    public Snapshot restore() throws Exception { shell.exec("sh "+HELPER+" restore"); return probe(); }
    public Snapshot rollback(String token) throws Exception {
        if(!token.matches("[0-9a-f-]{36}")) throw new IOException("试用编号无效");
        shell.exec("sh "+HELPER+" rollback "+token); return probe();
    }
    @Override public void close() { shell.close(); }

    public static final class Snapshot {
        public final int[] min=new int[3],max=new int[3],masks=new int[4];
        public final String[] governors=new String[3];
        public final List<Integer>[] frequencies;
        public final List<Integer> gpuFrequencies;
        public final int gpuMin,gpuMax,remaining;
        public final String phase,token,actor,foreground,last,watchError;
        public final boolean matches,auto,watch;
        private final String target,legacy,targetGpu;
        @SuppressWarnings("unchecked") Snapshot(String text) throws Exception {
            Map<String,String> data=new HashMap<>();
            for(String line:text.split("\n")) { int i=line.indexOf('='); if(i>0) data.put(line.substring(0,i),line.substring(i+1)); }
            frequencies=(List<Integer>[])new List<?>[3];
            for(int i=0;i<3;i++) {
                String[] v=Objects.requireNonNull(data.get("policy"+PlatformProfile.POLICIES[i]),"CPU 数据不完整").split(";",-1);
                if(v.length!=4) throw new IOException("CPU 档位无法识别"); min[i]=Integer.parseInt(v[0]); max[i]=Integer.parseInt(v[1]); governors[i]=v[2]; frequencies[i]=table(v[3]);
            }
            String[] gpu=Objects.requireNonNull(data.get("gpu"),"GPU 数据不完整").split(";",-1); if(gpu.length!=3) throw new IOException("GPU 档位无法识别");
            gpuMin=Integer.parseInt(gpu[0]); gpuMax=Integer.parseInt(gpu[1]); gpuFrequencies=table(gpu[2]);
            String[] groups={"background","system-background","foreground","top-app"}; for(int i=0;i<4;i++) masks[i]=Integer.parseInt(data.get(groups[i]));
            phase=data.getOrDefault("phase","none"); token=data.getOrDefault("token",""); actor=data.getOrDefault("actor","global"); remaining=Integer.parseInt(data.getOrDefault("remaining","0"));
            matches=data.getOrDefault("match","1").equals("1"); auto=data.getOrDefault("auto","0").equals("1"); watch=data.getOrDefault("watch","0").equals("1");
            foreground=data.getOrDefault("foregroundApp",""); last=data.getOrDefault("last",""); watchError=data.getOrDefault("watchError","");
            target=data.getOrDefault("target",""); targetGpu=data.getOrDefault("targetGpu",""); legacy=data.getOrDefault("legacyTarget","");
        }
        private static List<Integer> table(String s) throws IOException {
            TreeSet<Integer> out=new TreeSet<>(); for(String n:s.trim().split("\\s+")) { try { out.add(Integer.parseInt(n)); } catch(NumberFormatException e) { throw new IOException("设备频率列表无效",e); } }
            return new ArrayList<>(out);
        }
        public PlatformProfile current() throws Exception { return new PlatformProfile(min,max,gpuMin,gpuMax,masks); }
        public String status(int rechecks) {
            if(phase.equals("trial")) return "正在试用";
            if(phase.equals("restoring")) return "正在恢复";
            if(phase.equals("restore-failed")) return "恢复未完成，请重试";
            if(phase.equals("active") || phase.equals("legacy")) return matches ? "配置已保存，当前读数一致" : rechecks<3 ? "目标已保存，正在自动复核读数" : "目标已保存，部分当前值不同";
            return last.equals("expired") ? "试用结束，已回到上一配置" : "未由本版接管";
        }
        public PlatformProfile intent() throws Exception {
            if(!target.isEmpty()) {
                String[] v=target.split(" "), g=targetGpu.split(";"); if(v.length!=12 || g.length!=2) throw new IOException("已保存的目标值不完整");
                int[] lo=new int[3],hi=new int[3],sets=new int[4]; for(int i=0;i<3;i++) {lo[i]=Integer.parseInt(v[i*2]);hi[i]=Integer.parseInt(v[i*2+1]);} for(int i=0;i<4;i++) sets[i]=Integer.parseInt(v[8+i]);
                return new PlatformProfile(lo,hi,Integer.parseInt(g[0]),Integer.parseInt(g[1]),sets);
            }
            if(!legacy.isEmpty()) {
                String[] v=legacy.split(" "); if(v.length==8) { int[] lo=new int[3],hi=new int[3],sets=masks.clone(); for(int i=0;i<3;i++){lo[i]=Integer.parseInt(v[2*i]);hi[i]=Integer.parseInt(v[2*i+1]);} sets[0]=Integer.parseInt(v[7]); sets[3]=Integer.parseInt(v[6]); return new PlatformProfile(lo,hi,gpuMin,gpuMax,sets); }
            }
            return current();
        }
        public void validate(PlatformProfile p) throws IOException {
            for(int i=0;i<3;i++) { if(!frequencies[i].contains(p.min[i]) || !frequencies[i].contains(p.max[i])) throw new IOException("CPU 频率不在设备档位中"); if(!governors[i].equals("sugov_ext")) throw new IOException("请先恢复原生 sugov_ext"); }
            if(!gpuFrequencies.contains(p.gpuMin) || !gpuFrequencies.contains(p.gpuMax)) throw new IOException("GPU 频率不在实际驱动档位中");
        }
        private static int ceiling(List<Integer> values,int n) { return values.stream().filter(f->f>=n).findFirst().orElse(values.get(values.size()-1)); }
        public PolicyBook defaults() throws Exception {
            PlatformProfile base=intent();
            int[] lo={frequencies[0].get(0),frequencies[1].get(0),frequencies[2].get(0)};
            if(target.isEmpty() && legacy.isEmpty()) base=new PlatformProfile(lo,base.max,gpuFrequencies.get(0),base.gpuMax,base.masks);
            int[] eco=new int[3],full=new int[3];
            int[] ecoCaps={1200000,1800000,2200000}; for(int i=0;i<3;i++) {eco[i]=Math.max(lo[i],ceiling(frequencies[i],ecoCaps[i]));full[i]=frequencies[i].get(frequencies[i].size()-1);}
            return new PolicyBook(new PlatformProfile[]{new PlatformProfile(lo,eco,gpuFrequencies.get(0),ceiling(gpuFrequencies,624000),new int[]{15,15,63,63}),base,new PlatformProfile(new int[]{frequencies[0].get(0),frequencies[1].get(0),frequencies[2].get(0)},full,gpuFrequencies.get(0),gpuFrequencies.get(gpuFrequencies.size()-1),new int[]{15,15,255,255})});
        }
    }
}
