package io.github.sturgeonissleepy.lighttuner;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Bounded, private session files. Export is explicit and never uploads data. */
public final class SessionArchive {
    public static final String[] FILES={"record.state","record.id","record.meta.json","record.threads.tsv","record.frequencies.csv","record.timeline.csv","record.timeline-capped","record.metrics.csv","record.frames.csv","record.layers.tsv","record.frame-bins.tsv","record.frames.state"};
    private final File home;
    public SessionArchive(File files){home=new File(files,"sessions");}
    public boolean removed(String id)throws IOException {File last=new File(home,"last-session");return last.isFile()&&new String(Files.readAllBytes(last.toPath()),StandardCharsets.UTF_8).equals(id)&&!new File(home,id).isDirectory();}
    public void keep(Recording recording)throws IOException {
        if(!Arrays.asList("complete","stopped","interrupted").contains(recording.phase)||recording.summaryMs<1000||!recording.id.matches("[a-f0-9-]{36}"))return;
        home.mkdirs();File destination=new File(home,recording.id);if(removed(recording.id))return;if(destination.isDirectory()){remember(recording.id);return;}
        File[] leftovers=home.listFiles();if(leftovers!=null)for(File pending:leftovers)if(pending.isDirectory()&&pending.getName().matches("[a-f0-9-]{36}\\.pending"))removeFiles(pending);
        File pending=new File(home,recording.id+".pending");pending.mkdirs();
        for(String name:FILES){File source=new File(recording.directory,name);if(source.isFile())Files.copy(source.toPath(),new File(pending,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        if(!pending.renameTo(destination))throw new IOException("录制历史保存失败");
        remember(recording.id);
        List<File> all=list("*");for(int i=12;i<all.size();i++)remove(all.get(i));
    }
    public List<File> list(String pkg)throws IOException {
        List<File> out=new ArrayList<>();File[] directories=home.listFiles();if(directories==null)return out;
        for(File dir:directories)if(dir.isDirectory()&&dir.getName().matches("[a-f0-9-]{36}")){if(pkg.equals("*")||metadata(dir).optString("packageName","*").equals(pkg))out.add(dir);}
        out.sort(Comparator.comparingLong(File::lastModified).reversed());return out;
    }
    public static JSONObject metadata(File dir)throws IOException {File meta=new File(dir,"record.meta.json");try{return meta.isFile()?new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8)):new JSONObject();}catch(JSONException e){throw new IOException("历史说明损坏",e);}}
    private void remember(String id)throws IOException {File last=new File(home,"last-session");if(!last.isFile()||!new String(Files.readAllBytes(last.toPath()),StandardCharsets.UTF_8).equals(id))Files.write(last.toPath(),id.getBytes(StandardCharsets.UTF_8));}
    public void remove(File dir)throws IOException {if(!dir.getCanonicalFile().getParentFile().equals(home.getCanonicalFile())||!dir.getName().matches("[a-f0-9-]{36}"))throw new IOException("历史路径无效");removeFiles(dir);}
    private static void removeFiles(File dir)throws IOException {for(String name:FILES){File file=new File(dir,name);if(file.exists()&&!file.delete())throw new IOException("历史删除失败");}if(!dir.delete())throw new IOException("历史删除失败");}
    public static JSONObject summary(Recording recording)throws JSONException {
        JSONArray threads=new JSONArray();for(Recording.ThreadSample t:recording.threads){threads.put(new JSONObject().put("packageName",t.packageName).put("versionCode",t.versionCode).put("process",t.process).put("pid",t.pid).put("tid",t.tid).put("startTicks",t.start).put("threadName",t.name).put("averageCpuPercent",t.average).put("peakCpuPercent",t.peak).put("observedCpuTimeMs",Math.round(t.average*recording.summaryMs/100)).put("allowedCpuMask",t.mask).put("samples",t.samples).put("criticalHeuristic",Recording.critical(t)).put("candidateReason",recording.candidateReason(t)));}
        double watts=0,temperature=Double.NaN;int count=0,charging=0;for(double[] p:recording.metrics){if(p[4]==1)charging++;if(p[4]==0&&Double.isFinite(p[1])&&Double.isFinite(p[2])){watts+=Math.abs(p[1]*p[2]/1e12);count++;}if(Double.isFinite(p[3]))temperature=Double.isFinite(temperature)?Math.max(temperature,p[3]/10):p[3]/10;}JSONObject power=new JSONObject().put("averageDischargeBatterySideW",count>0?watts/count:JSONObject.NULL).put("dischargingSamples",count).put("chargingSamples",charging).put("maxBatteryTemperatureC",Double.isFinite(temperature)?temperature:JSONObject.NULL).put("estimatedFrom","abs(currentUa * voltageUv) / 1e12");
        return new JSONObject().put("schemaVersion",2).put("sessionId",recording.id).put("phase",recording.phase).put("context",recording.context).put("durationMs",recording.summaryMs).put("elapsedMs",recording.elapsedMs).put("intervalMs",1000).put("threadLimitReached",recording.capped).put("timelineLimitReached",new File(recording.directory,"record.timeline-capped").exists()).put("frames",recording.frames.layers.isEmpty()?JSONObject.NULL:recording.frames.json()).put("power",recording.metrics.isEmpty()?JSONObject.NULL:power).put("coreResidency",JSONObject.NULL).put("threads",threads);
    }
    public static void export(Recording recording,OutputStream destination)throws Exception {
        if(recording.running()||recording.summaryMs<1000)throw new IOException("录制结束后才能导出");
        try(ZipOutputStream zip=new ZipOutputStream(destination,StandardCharsets.UTF_8)){
            entry(zip,"summary.json",summary(recording).toString(2));
            entry(zip,"profile-before.json",recording.context.optJSONObject("profileBefore")==null?"null":recording.context.getJSONObject("profileBefore").toString(2));
            entry(zip,"README.txt","轻调 AI 分析包\n请先识别主线程、渲染、音频和其他关键线程，结合线程占用及 CPU/GPU 频率提出候选策略，说明不确定性；不能仅因 CPU 占用高就认定空转。天学网 Thread-N 可参考 CPU0–3 的历史思路，但只对本次观察到的具体线程生成建议。\n中断记录的线程汇总可能只到最后一次十秒检查点，durationMs 是汇总时长，elapsedMs 是状态中的采样时长，时间线可能覆盖更长时间。线程 CPU 100% 代表一个核心，average 是采样观察到的 CPU 时间除以整场统计时长，first baseline 和短暂线程可能漏计。timeline 仅保存有 CPU 时间增量的区间，缺行不代表确定为零，intervalMs 表示该线程两次观测间隔。lastCpu 是 stat 中最近运行核心，不是驻留时间或迁移次数。\n频率为每秒瞬时采样，不代表精确频率驻留；GPU -1 为缺失。仅使用实际有值的帧和电池侧估算数据，见 FRAME-README.txt；没有精确整机功率、SoC 温度或 sched_switch 数据，不得编造。timeline 上限 200000 行。核心规则受应用 cpuset 约束，同名实例均匹配，版本升级后不沿用。\n此包包含包名、版本、进程名与 PID/TID 等本机记录，无应用内容。导出不联网，分享前请自行检查。所有建议需先试用 20 秒、确认后保留。\n");
            entry(zip,"FRAME-README.txt","FrameTimeline 的应用帧用 PID 归属到目标应用，再以 displayFrameToken 关联显示帧实际呈现时间，不能将应用时间线结束时间当作屏幕呈现时间。按单一窗口去重，不累加所有窗口的 FPS。frames.csv 中 presentNs=0 表示没有有效呈现时间；intervalMs、appTimelineMs、expectedTimelineMs 为 -1 时缺失。被丢弃的帧不计呈现 FPS。\nSurfaceFlinger latency 路径只有窗口呈现时间，没有系统 jank 分类和应用帧时间线时长，不能推算卡顿分类。卡顿比例分母为具有分类的应用缓冲帧，包含被丢弃帧；不是 Scene 的 Jitter 指标。P95/P99 呈现间隔为 1ms 直方图近似，上限1000ms；应用帧时间线时长为0.1ms近似，上限100ms，饱和值不是准确的长帧时长。\n电池侧功率为电流电压估算，不等于精确整机功率。充电样本不计入非充电平均，电池温度不是 SoC 温度。没有精确核心驻留、迁移次数、sched_switch 或线程频率暴露。\n");
            for(String[] item:new String[][]{{"record.threads.tsv","threads.tsv"},{"record.frequencies.csv","frequency.csv"},{"record.timeline.csv","thread-timeline.csv"},{"record.metrics.csv","metrics.csv"},{"record.frames.csv","frames.csv"},{"record.layers.tsv","frame-layers.tsv"},{"record.frame-bins.tsv","frame-bins.tsv"}}){File file=new File(recording.directory,item[0]);if(!file.isFile())continue;zip.putNextEntry(new ZipEntry(item[1]));Files.copy(file.toPath(),zip);zip.closeEntry();}
        }
    }
    private static void entry(ZipOutputStream zip,String name,String text)throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
