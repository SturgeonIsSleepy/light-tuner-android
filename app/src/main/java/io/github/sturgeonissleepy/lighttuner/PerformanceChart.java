package io.github.sturgeonissleepy.lighttuner;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Two bounded traces on the session clock. Tap a second to inspect actual frames. */
public final class PerformanceChart extends View {
    private final Recording session;
    private final FrameData.Layer layer;
    private final java.util.function.IntConsumer selected;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    public PerformanceChart(Context context,Recording session,FrameData.Layer layer,java.util.function.IntConsumer selected){super(context);this.session=session;this.layer=layer;this.selected=selected;density=getResources().getDisplayMetrics().density;setContentDescription("应用每秒呈现帧数和电池侧功率，点击时间查看帧与线程采样");setFocusable(true);}
    @Override protected void onDraw(Canvas c){float left=42*density,right=getWidth()-10*density,top=20*density,middle=getHeight()/2f,bottom=getHeight()-32*density,duration=Math.max(1000,session.elapsedMs);paint.setTextSize(11*density);paint.setColor(getContext().getColor(R.color.app_muted));paint.setStyle(Paint.Style.FILL);c.drawText("FPS",0,top,paint);c.drawText("W",14*density,middle+18*density,paint);c.drawLine(left,middle-16*density,right,middle-16*density,paint);c.drawLine(left,bottom,right,bottom,paint);int peak=60;if(layer!=null)for(int n:layer.bins)peak=Math.max(peak,n);if(layer!=null&&layer.presented>0){paint.setColor(getContext().getColor(R.color.app_primary));paint.setStrokeWidth(2*density);for(int i=0;i<600&&i*1000<duration;i++){float start=left+i*1000/duration*(right-left),end=left+Math.min(duration,(i+1)*1000)/duration*(right-left),y=middle-16*density-layer.bins[i]/(float)peak*(middle-top-30*density);c.drawLine(start,y,end,y,paint);}}else c.drawText("等待可归属的应用帧",left,top+25*density,paint);paint.setColor(getContext().getColor(android.R.color.system_accent2_400));paint.setStrokeWidth(2*density);for(double[] p:session.metrics){if(!Double.isFinite(p[1])||!Double.isFinite(p[2]))continue;double watts=Math.abs(p[1]*p[2]/1e12);float x=left+(float)(p[0]/duration)*(right-left),y=bottom-(float)Math.min(1,watts/12)*(bottom-middle-24*density);c.drawCircle(x,y,1.5f*density,paint);}paint.setColor(getContext().getColor(R.color.app_muted));c.drawText(String.format(Locale.ROOT,"0 – %.1f 秒，点击时间查看详情",duration/1000),left,bottom+23*density,paint);}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)return true;if(e.getActionMasked()==MotionEvent.ACTION_UP){float ratio=(e.getX()-42*density)/Math.max(1,getWidth()-52*density);int second=Math.max(0,Math.min(599,(int)(ratio*session.elapsedMs/1000)));selected.accept(second);performClick();return true;}return super.onTouchEvent(e);}
    @Override public boolean performClick(){super.performClick();return true;}
}
