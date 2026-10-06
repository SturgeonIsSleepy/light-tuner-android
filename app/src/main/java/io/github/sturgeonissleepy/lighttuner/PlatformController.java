package io.github.sturgeonissleepy.lighttuner;

import android.content.Context;
import android.content.pm.*;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** An on-demand root session; optional application switching uses blocking logcat events. */
public final class PlatformController implements AutoCloseable {
    private static final String BASE="/data/adb/apptuner9400-v3", HELPER=BASE+"/controller.sh", RUNTIME=BASE+"/runtime.sh";
    private final RootShell shell;
    private final Context context;
    public PlatformController(Context context) throws Exception {
        this.context=context;
        shell=new RootShell();
        try {
            shell.exec("mkdir -p "+BASE+" && chmod 700 "+BASE);
            for(String name:new String[]{"mt6991.sh","runtime.sh","parameters.sh","scene.awk","lightcore","boot.sh"}){
                File asset=new File(context.getFilesDir(),name);try(InputStream in=context.getAssets().open(name);OutputStream out=new FileOutputStream(asset)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
                if(name.endsWith(".sh")||name.endsWith(".awk")){String text=new String(java.nio.file.Files.readAllBytes(asset.toPath()),StandardCharsets.UTF_8).replace("\r\n","\n").replace('\r','\n');java.nio.file.Files.write(asset.toPath(),text.getBytes(StandardCharsets.UTF_8));}
                String destination=name.equals("mt6991.sh")?HELPER:BASE+"/"+name;shell.exec("cp '"+asset.getAbsolutePath()+"' "+destination+".tmp && chmod 700 "+destination+".tmp && mv "+destination+".tmp "+destination);
            }
            shell.exec("mkdir -p /data/adb/service.d && cp "+BASE+"/boot.sh /data/adb/service.d/85-lighttuner.sh && chmod 700 /data/adb/service.d/85-lighttuner.sh");
            migrateBook();installPackages();RootRecovery.schedule(context,context.getSharedPreferences("MainActivity",0).getBoolean("persistent",false));
        }
        catch(Exception e) { shell.close(); throw e; }
    }
    public Snapshot probe() throws Exception { return new Snapshot(shell.exec("sh "+HELPER+" probe && sh "+RUNTIME+" thread-state")); }
    public Snapshot apply(PlatformProfile profile) throws Exception {
        Snapshot s=probe(); s.validate(profile);
        if(!s.threadToken.isEmpty())throw new IOException("请先确认或撤回线程试用");
        shell.exec("sh "+HELPER+" trial global "+profile.arguments()); return probe();
    }
    public Snapshot confirm(String token) throws Exception {
        if(!token.matches("[0-9a-f-]{36}")) throw new IOException("试用编号无效");
        shell.exec("sh "+HELPER+" confirm "+token);setPersistent(true);return probe();
    }
    public void installBook(PolicyBook book) throws Exception {
        Snapshot s=probe(); for(PlatformProfile p:book.presets) s.validate(p); for(PolicyBook.Rule r:book.apps.values()) if(r.custom!=null) s.validate(r.custom);
        File rules=new File(context.getFilesDir(),"rules.txt"); try(OutputStream out=new FileOutputStream(rules)) { out.write(book.rootRules().getBytes(StandardCharsets.UTF_8)); }
        shell.exec("cp '"+rules.getAbsolutePath()+"' "+BASE+"/rules.tmp && chmod 600 "+BASE+"/rules.tmp && mv "+BASE+"/rules.tmp "+BASE+"/rules");
        File cache=new File(context.getFilesDir(),"book.json");try(OutputStream out=new FileOutputStream(cache)){out.write(book.json().toString().getBytes(StandardCharsets.UTF_8));}shell.exec("cp '"+cache.getAbsolutePath()+"' "+BASE+"/book.json");
        PlatformProfile balanced=book.presets[1];int[] hi=balanced.max.clone(),sets=balanced.masks.clone();int[] floor={1600000,2400000,3000000};for(int i=0;i<3;i++)hi[i]=Math.max(hi[i],Snapshot.ceiling(s.frequencies[i],floor[i]));sets[2]|=240;sets[3]|=240;
        PlatformProfile window=new PlatformProfile(balanced.min,hi,balanced.gpuMin,Math.max(balanced.gpuMax,Snapshot.ceiling(s.gpuFrequencies,806000)),sets,balanced.upRateUs,balanced.downRateUs,balanced.topAppMin);writeRoot("window-floor",window.arguments()+"\n");
    }
    public Snapshot startAuto(PolicyBook book) throws Exception { installBook(book);writeRoot("resume-profile",book.profile(book.global).arguments()+"\n");shell.exec("touch "+BASE+"/persistent-enabled "+BASE+"/want-auto && sh "+HELPER+" start && sh "+RUNTIME+" ensure");setPersistent(true);return probe(); }
    public Snapshot stopAuto(PolicyBook book) throws Exception { shell.exec("rm -f "+BASE+"/want-auto "+BASE+"/scene.applied "+BASE+"/float-until; echo normal > "+BASE+"/window_mode; sh "+HELPER+" stop");installBook(book);shell.exec("sh "+HELPER+" switch global");writeRoot("resume-profile",book.profile(book.global).arguments()+"\n");return probe(); }
    public Snapshot reload(PolicyBook book) throws Exception {
        installBook(book);Snapshot s=probe();if(s.auto){shell.exec("rm -f "+BASE+"/scene.applied; sh "+RUNTIME+" scene");writeRoot("resume-profile",book.profile(book.global).arguments()+"\n");}return probe();
    }
    public Snapshot restore() throws Exception { shell.exec("sh "+HELPER+" restore");setPersistent(false);return probe(); }
    public Snapshot rollback(String token) throws Exception {
        if(!token.matches("[0-9a-f-]{36}")) throw new IOException("试用编号无效");
        shell.exec("sh "+HELPER+" rollback "+token); return probe();
    }
    @Override public void close() { shell.close(); }
    private void setPersistent(boolean enabled){context.getSharedPreferences("MainActivity",0).edit().putBoolean("persistent",enabled).apply();RootRecovery.schedule(context,enabled);}
    private void writeRoot(String name,String data) throws Exception {File file=new File(context.getFilesDir(),name);try(OutputStream out=new FileOutputStream(file)){out.write(data.getBytes(StandardCharsets.UTF_8));}shell.exec("cp '"+file.getAbsolutePath()+"' "+BASE+"/"+name+".tmp && chmod 600 "+BASE+"/"+name+".tmp && mv "+BASE+"/"+name+".tmp "+BASE+"/"+name);}
    private void migrateBook() throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("MainActivity",0);if(!prefs.getString("book3","").isEmpty())return;
        File file=new File(context.getFilesDir(),"migration.xml");file.createNewFile();String path=file.getAbsolutePath();shell.exec("if [ -r /data/user/0/cc.ccwu.apptuner/shared_prefs/MainActivity.xml ]; then cat /data/user/0/cc.ccwu.apptuner/shared_prefs/MainActivity.xml > '"+path+"'; fi");
        try(FileInputStream in=new FileInputStream(file)){if(file.length()>0){XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(in,"UTF-8");for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next())if(event==XmlPullParser.START_TAG&&parser.getName().equals("string")&&"book3".equals(parser.getAttributeValue(null,"name"))){String source=parser.nextText();PolicyBook.parse(source);prefs.edit().putString("book3",source).apply();break;}}}finally{file.delete();}
    }
    public void installPackages() throws Exception {
        StringBuilder map=new StringBuilder();PackageManager pm=context.getPackageManager();int count=0;for(ApplicationInfo app:pm.getInstalledApplications(0)){if(++count>2048)break;try{PackageInfo info=pm.getPackageInfo(app.packageName,0);map.append(app.uid).append('\t').append(info.getLongVersionCode()).append('\t').append(app.packageName).append('\t').append(app.sourceDir).append('\n');}catch(PackageManager.NameNotFoundException ignored){}}
        writeRoot("packages.tsv",map.toString());
    }
    public Snapshot recover() throws Exception {shell.exec("sh "+RUNTIME+" recover");return probe();}
    public Snapshot installThreads(ThreadRules rules) throws Exception {writeRoot("threads.staged",rules.compiled());installPackages();shell.exec("sh "+RUNTIME+" thread-install");return probe();}
    public Snapshot trialThreads(ThreadRules rules)throws Exception {writeRoot("threads.staged",rules.compiled());installPackages();shell.exec("sh "+RUNTIME+" thread-trial");return probe();}
    public Snapshot confirmThreads(String token)throws Exception {threadToken(token);shell.exec("sh "+RUNTIME+" thread-confirm "+token);return probe();}
    public Snapshot rollbackThreads(String token)throws Exception {threadToken(token);shell.exec("sh "+RUNTIME+" thread-revert "+token+" && sh "+RUNTIME+" recover");return probe();}
    private static void threadToken(String token)throws IOException {if(!token.matches("[0-9a-f-]{36}"))throw new IOException("线程试用编号无效");}
    public Snapshot record(int seconds,String pkg) throws Exception {
        if(seconds<5||seconds>600||!pkg.equals("*")&&!pkg.matches("[A-Za-z0-9_.]+"))throw new IOException("录制范围无效");
        Snapshot s=probe();String saved=context.getSharedPreferences("MainActivity",0).getString("book3","");
        org.json.JSONObject metadata=new org.json.JSONObject().put("packageName",pkg).put("startedAt",System.currentTimeMillis()).put("platform","mt6991").put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE).put("profileBefore",s.current().json()).put("actorBefore",s.actor).put("appliedBefore",!s.phase.equals("none"));
        if(!pkg.equals("*"))metadata.put("versionCode",Long.toString(context.getPackageManager().getPackageInfo(pkg,0).getLongVersionCode()));
        if(!saved.isEmpty())metadata.put("savedProfile",PolicyBook.parse(saved).forApp(pkg).json());ThreadRules before=ThreadRules.parse(context.getSharedPreferences("MainActivity",0).getString("threads",""));if(!pkg.equals("*"))before.entries.removeIf(rule->!rule.packageName.equals(pkg));metadata.put("threadRulesBefore",new org.json.JSONObject(before.json())).put("kernel",System.getProperty("os.version","")).put("windowMode",s.windowMode).put("cpuGovernors",new org.json.JSONArray(Arrays.asList(s.governors))).put("responseActual",new org.json.JSONArray(Arrays.asList(s.tuningActual)));
        writeRoot("record.context",metadata.toString());installPackages();shell.exec("sh "+RUNTIME+" record-start "+seconds+" '"+pkg+"'");return probe();
    }
    public Snapshot stopRecord() throws Exception {shell.exec("sh "+RUNTIME+" record-stop");return probe();}
    public Recording readRecording() throws Exception {
        String state=shell.exec("sh "+RUNTIME+" record-state").trim();boolean running=state.startsWith("recording\t")||state.startsWith("starting\t")||state.startsWith("processing\t");File directory=new File(context.getFilesDir(),"recording");directory.mkdirs();java.nio.file.Files.write(new File(directory,"record.state").toPath(),(state+"\n").getBytes(StandardCharsets.UTF_8));
        StringJoiner commands=new StringJoiner(" && ");for(String name:SessionArchive.FILES){if(name.equals("record.state")||running&&(name.startsWith("record.timeline")||name.equals("record.frames.csv")))continue;File destination=new File(directory,name);destination.createNewFile();commands.add("if [ -r "+BASE+"/"+name+" ]; then cat "+BASE+"/"+name+" > '"+destination.getAbsolutePath()+"'; else rm -f '"+destination.getAbsolutePath()+"'; fi");}shell.exec(commands.toString());
        Recording recording=Recording.read(directory);SessionArchive archive=new SessionArchive(context.getFilesDir());archive.keep(recording);if(archive.removed(recording.id))return new Recording();if(!recording.running()&&recording.id.matches("[a-f0-9-]{36}")){File saved=new File(context.getFilesDir(),"sessions/"+recording.id);if(saved.isDirectory())recording.directory=saved;}return recording;
    }

    public static final class Snapshot {
        public final int[] min=new int[3],max=new int[3],masks=new int[4];
        public final String[] governors=new String[3];
        public final String[] tuningActual;
        public final int upRateUs,downRateUs,topAppMin;
        public final List<Integer>[] frequencies;
        public final List<Integer> gpuFrequencies;
        public final int gpuMin,gpuMax,remaining,threadRemaining;
        public final String phase,token,actor,foreground,last,watchError,windowMode,threadToken,threadConfirmed;
        public final boolean matches,auto,watch,persistent,guardian;
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
            threadToken=data.getOrDefault("threadToken","");threadConfirmed=data.getOrDefault("threadConfirmed","");threadRemaining=Integer.parseInt(data.getOrDefault("threadRemaining","0"));
            tuningActual=data.getOrDefault("tuningActual","-;-;-;-;-;-;-").split(";",-1);String[] tune=data.getOrDefault("tuningTarget","-1 -1 -1").trim().split(" +");upRateUs=Integer.parseInt(tune[0]);downRateUs=Integer.parseInt(tune[1]);topAppMin=Integer.parseInt(tune[2]);
            matches=data.getOrDefault("match","1").equals("1")&&data.getOrDefault("tuningMatch","1").equals("1"); auto=data.getOrDefault("auto","0").equals("1"); watch=data.getOrDefault("watch","0").equals("1");
            foreground=data.getOrDefault("foregroundApp",""); last=data.getOrDefault("last",""); watchError=data.getOrDefault("watchError","");
            persistent=data.getOrDefault("persistent","0").equals("1");guardian=data.getOrDefault("guardian","0").equals("1");windowMode=data.getOrDefault("windowMode","normal");
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
                return new PlatformProfile(lo,hi,Integer.parseInt(g[0]),Integer.parseInt(g[1]),sets,upRateUs,downRateUs,topAppMin);
            }
            if(!legacy.isEmpty()) {
                String[] v=legacy.split(" "); if(v.length==8) { int[] lo=new int[3],hi=new int[3],sets=masks.clone(); for(int i=0;i<3;i++){lo[i]=Integer.parseInt(v[2*i]);hi[i]=Integer.parseInt(v[2*i+1]);} sets[0]=Integer.parseInt(v[7]); sets[3]=Integer.parseInt(v[6]); return new PlatformProfile(lo,hi,gpuMin,gpuMax,sets); }
            }
            return current();
        }
        public void validate(PlatformProfile p) throws IOException {
            validateSaved(p);
            for(int i=0;i<6;i++)if((i%2==0?p.upRateUs:p.downRateUs)>=0&&(tuningActual.length<7||tuningActual[i].equals("-")))throw new IOException("设备未提供所选 sugov_ext 响应接口");if(p.topAppMin>=0&&(tuningActual.length<7||tuningActual[6].equals("-")))throw new IOException("设备未提供顶层进程 uclamp 接口");
        }
        public void validateSaved(PlatformProfile p) throws IOException {
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
