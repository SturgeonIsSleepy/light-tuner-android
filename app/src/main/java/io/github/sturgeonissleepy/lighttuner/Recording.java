package io.github.sturgeonissleepy.lighttuner;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Recording {
    public String id="",phase="none";
    public int elapsedMs,summaryMs,count;
    public boolean capped;
    public File directory;
    public JSONObject context=new JSONObject();
    public FrameData frames=new FrameData();
    public final List<double[]> metrics=new ArrayList<>();
    public final List<ThreadSample> threads=new ArrayList<>();
    public final List<String> frequencyRows=new ArrayList<>();
    public static final class ThreadSample {
        public String packageName,versionCode,process,name,start;
        public int uid,pid,tid,mask,samples;
        public double average,peak;
    }
    public static Recording read(File directory) throws IOException {
        Recording out=new Recording();out.directory=directory;File metadata=new File(directory,"record.meta.json");if(metadata.isFile()&&metadata.length()>0)try{out.context=new JSONObject(new String(java.nio.file.Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));}catch(JSONException e){throw new IOException("录制说明无法读取",e);}File state=new File(directory,"record.state");if(!state.isFile()||state.length()==0)return out;
        String[] value=readText(state).trim().split("\t");if(value.length!=4)throw new IOException("录制状态不完整");out.phase=value[0];out.elapsedMs=Integer.parseInt(value[1]);out.count=Integer.parseInt(value[2]);out.capped=value[3].equals("1");File id=new File(directory,"record.id");if(id.isFile())out.id=readText(id).trim();
        File table=new File(directory,"record.threads.tsv");if(table.isFile())try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(table),StandardCharsets.UTF_8))){String header=in.readLine();if(header!=null&&header.startsWith("#durationMs=")){out.summaryMs=Integer.parseInt(header.substring(12));in.readLine();}String line;while((line=in.readLine())!=null){String[] v=line.split("\t",-1);if(v.length!=12)continue;ThreadSample t=new ThreadSample();t.packageName=v[0];t.versionCode=v[1];t.process=v[2];t.uid=Integer.parseInt(v[3]);t.pid=Integer.parseInt(v[4]);t.tid=Integer.parseInt(v[5]);t.start=v[6];t.name=v[7];t.average=Double.parseDouble(v[8]);t.peak=Double.parseDouble(v[9]);t.mask=Integer.parseInt(v[10]);t.samples=Integer.parseInt(v[11]);out.threads.add(t);if(out.threads.size()>=8192)break;}}
        out.threads.sort(Comparator.comparingDouble((ThreadSample t)->t.average).reversed());File frequencies=new File(directory,"record.frequencies.csv");if(frequencies.isFile())try(BufferedReader in=new BufferedReader(new FileReader(frequencies))){in.readLine();String line;while((line=in.readLine())!=null&&out.frequencyRows.size()<601)out.frequencyRows.add(line);}out.frames=FrameData.read(directory);if(out.phase.equals("interrupted"))out.frames.loss=true;File metric=new File(directory,"record.metrics.csv");if(metric.isFile())try(BufferedReader in=new BufferedReader(new FileReader(metric))){in.readLine();String line;while((line=in.readLine())!=null&&out.metrics.size()<601){String[] v=line.split(",",-1);if(v.length!=6)continue;double[] point=new double[6];for(int i=0;i<6;i++)point[i]=v[i].isEmpty()?Double.NaN:Double.parseDouble(v[i]);out.metrics.add(point);}}return out;
    }
    private static String readText(File file) throws IOException {try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){return Objects.requireNonNullElse(in.readLine(),"");}}
    public boolean running(){return phase.equals("recording")||phase.equals("starting")||phase.equals("processing");}
    public String packageName(){return context.optString("packageName","*");}
    public static boolean critical(ThreadSample t){String name=t.name.toLowerCase(Locale.ROOT);return t.pid==t.tid||name.equals("main")||name.contains("render")||name.contains("unity")||name.startsWith("binder:")||name.contains("jit")||name.contains("gc")||name.contains("audio")||name.contains("input")||name.contains("hwui")||name.contains("rhi")||name.contains("game")||name.contains("vulkan");}
    public String candidateReason(ThreadSample t){if(running()||summaryMs<15000||t.samples<10||t.average<30||critical(t)||!PolicyBook.packageAllowed(t.packageName))return "";return t.packageName.equals("com.up366.mobile")&&t.name.matches("Thread-[0-9]+")?"天学网 Thread-N 高占用候选，仅限制此线程，保留主线程和渲染线程":"平均占用较高，先确认用途，再试用限制 CPU7";}
    public JSONObject upload(String client) throws JSONException {
        PlatformProfile.require(!phase.equals("recording")&&!phase.equals("starting")&&elapsedMs>=1000&&id.matches("[a-f0-9-]{36}"),"完成或停止录制后才能上传");
        PlatformProfile.require(summaryMs>=1000,"有效线程统计不足一秒，请重新录制");
        LinkedHashMap<String,JSONObject> groups=new LinkedHashMap<>();for(ThreadSample t:threads){if(t.packageName.equals("unassigned"))continue;String key=t.packageName+'\t'+t.versionCode+'\t'+t.name;JSONObject group=groups.get(key);if(group==null){group=new JSONObject().put("packageName",t.packageName).put("versionCode",t.versionCode).put("threadName",t.name).put("averageCpu",0).put("peakCpu",0).put("instances",0);groups.put(key,group);}group.put("averageCpu",Math.min(800,group.getDouble("averageCpu")+t.average));group.put("peakCpu",Math.min(100,Math.max(group.getDouble("peakCpu"),t.peak)));group.put("instances",group.getInt("instances")+1);}
        PlatformProfile.require(!groups.isEmpty()&&groups.size()<=1000,"无可上传统计，或线程类别超过 1000 条，请选择单个应用重新录制");JSONArray list=new JSONArray();for(JSONObject group:groups.values())list.put(group);return new JSONObject().put("schemaVersion",1).put("platform","mt6991").put("sessionId",id).put("clientId",client).put("durationMs",summaryMs).put("threads",list);
    }
}
