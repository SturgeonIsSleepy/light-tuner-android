package io.github.sturgeonissleepy.lighttuner;

import android.app.job.*;
import android.content.*;

/** Fifteen-minute fallback only; the root supervisor performs the fast recovery. */
public final class RootRecovery extends JobService {
    private static final int ID=940050;
    public static void schedule(Context context,boolean enabled){JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(!enabled){scheduler.cancel(ID);return;}if(scheduler.getPendingJob(ID)==null)scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(context,RootRecovery.class)).setPersisted(true).setPeriodic(15*60*1000L).build());}
    public static void recover() throws Exception {try(RootShell root=new RootShell()){root.exec("if [ -f /data/adb/apptuner9400-v3/runtime.sh ]; then sh /data/adb/apptuner9400-v3/runtime.sh recover; fi");}}
    @Override public boolean onStartJob(JobParameters parameters){new Thread(()->{boolean retry=false;try{recover();}catch(Exception ignored){retry=true;}jobFinished(parameters,retry);},"LightTuner recovery").start();return true;}
    @Override public boolean onStopJob(JobParameters parameters){return false;}
}
