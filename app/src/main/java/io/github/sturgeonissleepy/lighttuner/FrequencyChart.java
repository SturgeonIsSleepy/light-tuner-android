package io.github.sturgeonissleepy.lighttuner;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** One static canvas, no drawing timer. Frequencies are actual readings, not caps. */
public final class FrequencyChart extends View {
    private final List<float[]> points=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;
    private final int textColor;
    private final float density;
    private final float duration;
    public FrequencyChart(Context context,Recording recording){super(context);density=getResources().getDisplayMetrics().density;duration=Math.max(1000,Math.max(recording.elapsedMs,recording.summaryMs));colors=new int[]{context.getColor(android.R.color.system_accent1_400),context.getColor(android.R.color.system_accent2_400),context.getColor(android.R.color.system_accent3_400),context.getColor(R.color.app_text)};textColor=context.getColor(R.color.app_muted);for(String row:recording.frequencyRows){String[] data=row.split(",");if(data.length!=5)continue;float[] p=new float[5];try{for(int i=0;i<5;i++)p[i]=Float.parseFloat(data[i]);points.add(p);}catch(NumberFormatException ignored){}}setContentDescription("CPU 三簇和 GPU 的实际频率曲线，GPU 缺失读数不绘制");}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float left=42*density,top=20*density,right=getWidth()-10*density,bottom=getHeight()-55*density;paint.setTextSize(11*density);paint.setColor(textColor);paint.setStyle(Paint.Style.FILL);canvas.drawText("3730",0,top+4*density,paint);canvas.drawText("0",18*density,bottom,paint);canvas.drawText("MHz",0,top-5*density,paint);paint.setStrokeWidth(density);canvas.drawLine(left,top,left,bottom,paint);canvas.drawLine(left,bottom,right,bottom,paint);if(points.isEmpty())return;float last=duration;for(int channel=1;channel<=4;channel++){Path path=new Path();boolean connected=false;for(float[] point:points){if(point[channel]<=0){connected=false;continue;}float x=left+point[0]/last*(right-left),y=bottom-Math.min(1,point[channel]/3730000f)*(bottom-top);if(connected)path.lineTo(x,y);else path.moveTo(x,y);connected=true;}paint.setColor(colors[channel-1]);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f*density);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);canvas.drawText(new String[]{"CPU0–3","CPU4–6","CPU7","GPU"}[channel-1],left+(channel-1)*(right-left)/4,bottom+28*density,paint);}paint.setColor(textColor);canvas.drawText(String.format(Locale.ROOT,"0 – %.0f 秒",last/1000),left,bottom+45*density,paint);}
}
