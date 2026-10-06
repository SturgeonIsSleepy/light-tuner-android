package io.github.sturgeonissleepy.lighttuner;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(!getEnabled(context))return;PendingResult pending=goAsync();new Thread(()->{try{RootRecovery.recover();}catch(Exception ignored){}finally{pending.finish();}},"LightTuner boot").start();}
    private boolean getEnabled(Context context){return context.getSharedPreferences("MainActivity",0).getBoolean("persistent",false);}
}
