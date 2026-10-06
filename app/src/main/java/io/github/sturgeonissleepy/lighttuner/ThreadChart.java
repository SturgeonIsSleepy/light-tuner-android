package io.github.sturgeonissleepy.lighttuner;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.MotionEvent;
import java.io.*;
import java.util.*;

/** Only the selected thread is loaded, with no redraw timer. */
public final class ThreadChart extends View {
    private final List<float[]> points=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density,duration;
    private final java.util.function.Consumer<float[]> selected;
    public ThreadChart(Context context,Recording session,List<float[]> samples,java.util.function.Consumer<float[]> selected){super(context);density=getResources().getDisplayMetrics().density;duration=Math.max(1000,Math.max(session.elapsedMs,session.summaryMs));points.addAll(samples);this.selected=selected;setContentDescription("此线程各有效采样区间的 CPU 占用，点击区间查看同期频率");setFocusable(true);}
    public static List<float[]> read(Recording session,Recording.ThreadSample thread)throws IOException {List<float[]> points=new ArrayList<>();File source=new File(session.directory,"record.timeline.csv");if(source.isFile())try(BufferedReader in=new BufferedReader(new FileReader(source))){in.readLine();String line;while((line=in.readLine())!=null){String[] v=line.split(",");if(v.length==7&&v[1].equals(Integer.toString(thread.pid))&&v[2].equals(Integer.toString(thread.tid))&&v[3].equals(thread.start)){points.add(new float[]{Float.parseFloat(v[0]),Float.parseFloat(v[4]),Float.parseFloat(v[5]),Float.parseFloat(v[6])});if(points.size()>=601)break;}}}catch(NumberFormatException e){throw new IOException("线程时间线无法读取",e);}return points;}
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN)return true;if(event.getActionMasked()==MotionEvent.ACTION_UP){float time=(event.getX()-42*density)/Math.max(1,getWidth()-52*density)*duration;for(float[] p:points)if(time>=p[0]-p[2]&&time<=p[0]){selected.accept(p);break;}performClick();return true;}return super.onTouchEvent(event);}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDraw(Canvas canvas){float left=42*density,top=20*density,right=getWidth()-10*density,bottom=getHeight()-32*density;paint.setTextSize(11*density);paint.setColor(getContext().getColor(R.color.app_muted));paint.setStrokeWidth(density);canvas.drawText("100%",0,top,paint);canvas.drawText("0",18*density,bottom,paint);canvas.drawLine(left,bottom,right,bottom,paint);paint.setColor(getContext().getColor(R.color.app_primary));paint.setStrokeWidth(3*density);for(float[] p:points){float x=left+p[0]/duration*(right-left),start=left+Math.max(0,p[0]-p[2])/duration*(right-left),y=bottom-Math.min(100,p[1])/100*(bottom-top);canvas.drawLine(start,y,x,y,paint);}paint.setColor(getContext().getColor(R.color.app_muted));canvas.drawText(String.format(Locale.ROOT,"0 – %.1f 秒",duration/1000),left,bottom+22*density,paint);if(points.isEmpty())canvas.drawText("暂无可用线程时间线",left,top+30*density,paint);}
}
