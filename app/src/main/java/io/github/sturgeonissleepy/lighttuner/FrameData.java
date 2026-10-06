package io.github.sturgeonissleepy.lighttuner;

import org.json.*;
import java.io.*;
import java.util.*;

/** Summary only; individual frame rows are streamed on demand. */
public final class FrameData {
    public boolean loss,capped;
    public int events,coveredMs;
    public final List<Layer> layers=new ArrayList<>();
    public static final class Layer {
        public int id,pid,presented,jank,classified,dropped;
        public String source,name;
        public double average,p95Interval,p99Interval,p95Work,p99Work;
        public final int[] bins=new int[601],bad=new int[601],known=new int[601];
        public double jankPercent(){return classified>0?100.0*jank/classified:Double.NaN;}
    }
    public static FrameData read(File directory)throws IOException {
        FrameData out=new FrameData();File state=new File(directory,"record.frames.state");if(state.isFile())try(BufferedReader in=new BufferedReader(new FileReader(state))){String line=in.readLine();if(line!=null){String[] v=line.split("\t");if(v.length==5){out.events=Integer.parseInt(v[1]);out.loss=v[2].equals("1");out.capped=v[3].equals("1");out.coveredMs=Integer.parseInt(v[4]);}}}
        File file=new File(directory,"record.layers.tsv");if(file.isFile())try(BufferedReader in=new BufferedReader(new FileReader(file))){in.readLine();String line;while((line=in.readLine())!=null&&out.layers.size()<64){String[] v=line.split("\t",-1);if(v.length!=13)continue;Layer l=new Layer();l.id=Integer.parseInt(v[0]);l.pid=Integer.parseInt(v[1]);l.source=v[2];l.name=v[3];l.presented=Integer.parseInt(v[4]);l.jank=Integer.parseInt(v[5]);l.classified=Integer.parseInt(v[6]);l.dropped=Integer.parseInt(v[7]);l.average=Double.parseDouble(v[8]);l.p95Interval=Double.parseDouble(v[9]);l.p99Interval=Double.parseDouble(v[10]);l.p95Work=Double.parseDouble(v[11]);l.p99Work=Double.parseDouble(v[12]);out.layers.add(l);}}
        file=new File(directory,"record.frame-bins.tsv");if(file.isFile())try(BufferedReader in=new BufferedReader(new FileReader(file))){in.readLine();String line;while((line=in.readLine())!=null){String[] v=line.split("\t");if(v.length!=5)continue;int second=Integer.parseInt(v[0]),id=Integer.parseInt(v[1]);if(second<0||second>600)continue;for(Layer l:out.layers)if(l.id==id){l.bins[second]=Integer.parseInt(v[2]);l.bad[second]=Integer.parseInt(v[3]);l.known[second]=Integer.parseInt(v[4]);break;}}}return out;
    }
    public Layer primary(){return layers.stream().filter(l->l.presented>1).max(Comparator.comparingInt((Layer l)->l.presented).thenComparingInt(l->l.source.equals("frametimeline")?1:0)).orElse(null);}
    public JSONObject json()throws JSONException {JSONArray items=new JSONArray();for(Layer l:layers)items.put(new JSONObject().put("id",l.id).put("pid",l.pid).put("source",l.source).put("name",l.name).put("presentedFrames",l.presented).put("classifiedFrames",l.classified).put("jankFrames",l.jank).put("droppedFrames",l.dropped).put("activeSpanAverageFps",l.average>=0?l.average:JSONObject.NULL).put("p95PresentIntervalMsApprox",l.p95Interval>=0?l.p95Interval:JSONObject.NULL).put("p99PresentIntervalMsApprox",l.p99Interval>=0?l.p99Interval:JSONObject.NULL).put("p95AppTimelineMsApprox",l.p95Work>=0?l.p95Work:JSONObject.NULL).put("p99AppTimelineMsApprox",l.p99Work>=0?l.p99Work:JSONObject.NULL));return new JSONObject().put("partial",loss).put("limitReached",capped).put("coveredMs",coveredMs).put("layers",items);}
    public static List<double[]> rows(File directory,int layer,int second)throws IOException {List<double[]> out=new ArrayList<>();File file=new File(directory,"record.frames.csv");if(!file.isFile())return out;try(BufferedReader in=new BufferedReader(new FileReader(file))){in.readLine();String line;while((line=in.readLine())!=null){String[] v=line.split(",");if(v.length!=11||Integer.parseInt(v[1])!=layer)continue;double at=Double.parseDouble(v[0]);if(at>=second*1000&&at<(second+1)*1000&&out.size()<360){double[] row=new double[11];for(int i=0;i<11;i++)row[i]=Double.parseDouble(v[i]);out.add(row);}}}return out;}
}
