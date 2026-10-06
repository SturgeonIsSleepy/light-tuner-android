package io.github.sturgeonissleepy.lighttuner;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Optional, session-only overlay. The Root recorder owns sampling and the deadline. */
public final class RecordingOverlay extends Service {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private RootShell shell;
    private TextView metrics;
    private WindowManager windows;
    private View panel;
    private String pkg="";
    private boolean destroyed,working;
    private int failures;
    private long expires;
    private final Runnable tick=()->read(false);
    private static final String BASE="/data/adb/apptuner9400-v3";
    public static void show(Context context){if(Settings.canDrawOverlays(context))context.startForegroundService(new Intent(context,RecordingOverlay.class));}
    @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("recording","性能记录",NotificationManager.IMPORTANCE_LOW));Intent launch=new Intent(this,MainActivity.class);PendingIntent pending=PendingIntent.getActivity(this,7,launch,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);startForeground(7,new Notification.Builder(this,"recording").setSmallIcon(R.drawable.ic_leaf).setContentTitle("轻调正在记录性能").setContentText("限时记录结束后自动关闭悬浮窗").setContentIntent(pending).setOngoing(true).build());if(!Settings.canDrawOverlays(this)){stopSelf();return;}windows=getSystemService(WindowManager.class);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int padding=dp(12);body.setPadding(padding,padding,padding,padding);GradientDrawable background=new GradientDrawable();background.setColor(getColor(R.color.app_surface));background.setCornerRadius(dp(14));body.setBackground(background);metrics=new TextView(this);metrics.setTextSize(12);metrics.setTextColor(getColor(R.color.app_text));metrics.setText("正在读取记录…");body.addView(metrics);LinearLayout actions=new LinearLayout(this);for(String label:new String[]{"详情","停止","隐藏"}){Button button=new Button(this);button.setText(label);button.setTextSize(12);button.setStateListAnimator(null);button.setElevation(0);button.setTextColor(getColor(R.color.app_primary));actions.addView(button,new LinearLayout.LayoutParams(0,dp(40),1));button.setOnClickListener(v->{if(label.equals("停止"))read(true);else if(label.equals("隐藏"))stopSelf();else{Intent intent=new Intent(this,MainActivity.class).putExtra("recordApp",pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(intent);}});}body.addView(actions);WindowManager.LayoutParams params=new WindowManager.LayoutParams(dp(260),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);params.gravity=Gravity.TOP|Gravity.END;params.y=dp(72);panel=body;try{windows.addView(panel,params);}catch(RuntimeException e){stopSelf();return;}metrics.setOnTouchListener(new View.OnTouchListener(){float x,y;int oldX,oldY;public boolean onTouch(View view,MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();oldX=params.x;oldY=params.y;return true;}if(event.getActionMasked()==MotionEvent.ACTION_MOVE){params.x=oldX-(int)(event.getRawX()-x);params.y=oldY+(int)(event.getRawY()-y);windows.updateViewLayout(panel,params);return true;}return false;}});read(false);}
    private void read(boolean stop){if(destroyed||working)return;if(expires>0&&SystemClock.elapsedRealtime()>expires){stopSelf();return;}working=true;io.execute(()->{String result=null,error=null;try{if(shell==null)shell=new RootShell();if(stop)shell.exec("sh "+BASE+"/runtime.sh record-stop");result=shell.exec("sh "+BASE+"/runtime.sh record-live");}catch(Exception e){error=e.getMessage();}String data=result,message=error;handler.post(()->{working=false;if(destroyed)return;if(message!=null){if(++failures>=3){stopSelf();return;}metrics.setText("记录读取失败\n"+message);handler.postDelayed(tick,3000);return;}failures=0;String[] lines=data.trim().split("\n");String phase=lines.length>0?lines[lines.length-1].split("\t")[0]:"none";if(!Arrays.asList("starting","recording","processing").contains(phase)){stopSelf();return;}try{String[] v=lines[0].split("\t");if(v.length==10){pkg=v[0];double fps=Double.parseDouble(v[2]),power=Double.parseDouble(v[7]),temperature=Double.parseDouble(v[8]);metrics.setText(String.format(Locale.ROOT,"%s\n已记录 %.0f 秒，最近 FPS %s\nCPU %s / %s / %s MHz\nGPU %s MHz\n电池侧 %s W，电池 %s℃%s",pkg,Double.parseDouble(v[1])/1000,fps>=0?String.format(Locale.ROOT,"%.0f",fps):"—",mhz(v[3]),mhz(v[4]),mhz(v[5]),mhz(v[6]),power>=0?String.format(Locale.ROOT,"%.2f",power):"—",temperature>-500?String.format(Locale.ROOT,"%.1f",temperature):"—",v[9].equals("1")?"，外部供电／充电":""));}}catch(NumberFormatException ignored){metrics.setText("等待有效采样…");}handler.postDelayed(tick,1000);});});}
    private static String mhz(String value){long n=Long.parseLong(value);return n>0?Long.toString(n/1000):"—";}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public int onStartCommand(Intent intent,int flags,int id){expires=SystemClock.elapsedRealtime()+660000;return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(windows!=null&&panel!=null&&panel.isAttachedToWindow())windows.removeView(panel);io.execute(()->{if(shell!=null)shell.close();});io.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
