package cc.ccwu.apptuner;

import android.app.*;
import android.animation.ValueAnimator;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private int BG,INK,GREEN,MUTED,SURFACE,CONTAINER,ON_PRIMARY,OUTLINE;
    private static final String[] GROUP_LABELS={"用户后台","系统后台","前台进程","顶层进程"};
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PlatformController controller;
    private PlatformController.Snapshot snapshot;
    private PlatformController.Snapshot displayedSnapshot;
    private PolicyBook book;
    private LinearLayout content;
    private ScrollView scroller;
    private TextView stateText,detailText,notice,trialText;
    private Switch takeoverSwitch;
    private AlertDialog trialDialog;
    private String dialogToken="";
    private boolean updatingSwitch;
    private final Runnable hideNotice=()->{if(ValueAnimator.areAnimatorsEnabled())notice.animate().alpha(0).translationY(dp(6)).setDuration(120).withEndAction(()->notice.setVisibility(View.GONE)).start();else notice.setVisibility(View.GONE);};
    private Spinner[] lower=new Spinner[4],upper=new Spinner[4];
    private Spinner globalChooser;
    private CheckBox[][] boxes=new CheckBox[4][8];
    private Button applyButton,autoButton,restoreButton,syncButton,readButton;
    private int tab=0,renderedTab=-1,editing=1,rechecks=0,ticks=0;
    private String editingApp=null;
    private boolean busy,reading,visible;
    private long trialDeadline;
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            if(!visible) return;
            updateStatus();
            if(snapshot!=null && !busy && !reading) {
                boolean pending=snapshot.phase.equals("trial") || snapshot.phase.equals("restoring");
                boolean settling=(snapshot.phase.equals("active") || snapshot.phase.equals("legacy")) && !snapshot.matches && rechecks<3;
                if((pending||settling)&&++ticks%2==0) { if(settling) rechecks++; MainActivity.this.run("自动复核读数",PlatformController::probe); }
            }
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        BG=getColor(R.color.app_background);INK=getColor(R.color.app_text);GREEN=getColor(R.color.app_primary);MUTED=getColor(R.color.app_muted);SURFACE=getColor(R.color.app_surface);CONTAINER=getColor(R.color.app_container);ON_PRIMARY=getColor(R.color.app_on_primary);OUTLINE=getColor(R.color.app_outline);
        if(!getPreferences(0).getBoolean("collectLogs",false))getPreferences(0).edit().remove("logs").apply();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});
        scroller=new ScrollView(this); content=column(); content.setPadding(dp(20),dp(20),dp(20),dp(24));scroller.addView(content);root.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
        notice=text("",14,BG,false);notice.setPadding(dp(20),dp(13),dp(20),dp(13));notice.setBackground(background(INK,18));notice.setVisibility(View.GONE);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams popup=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);popup.setMargins(dp(24),0,dp(24),dp(28));root.addView(notice,popup);setContentView(root);render();
        root.post(()->{WindowInsetsController b=getWindow().getInsetsController();boolean night=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;if(b!=null)b.setSystemBarsAppearance(night?0:light,light);});
    }
    @Override public void onResume(){super.onResume();visible=true;rechecks=0;ticks=0;updateStatus();if(!busy)run("读取配置与目标值",PlatformController::probe);}
    @Override public void onPause(){visible=false;handler.removeCallbacks(ticker);super.onPause();}
    @Override public void onStop(){super.onStop();if(!isChangingConfigurations())finish();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(trialDialog!=null)trialDialog.dismiss();io.execute(()->{if(controller!=null)controller.close();});io.shutdown();super.onDestroy();}
    private void navigateBack(){if(tab!=0){tab=0;editingApp=null;render();}else finish();}
    @Override public void onBackPressed(){navigateBack();}
    private interface Operation{PlatformController.Snapshot execute(PlatformController c)throws Exception;}
    private void run(String label,Operation op){
        boolean quiet=label.equals("自动复核读数")||label.equals("读取配置与目标值");
        if(busy||(quiet&&reading))return;if(quiet)reading=true;else{busy=true;rechecks=0;}updateStatus();
        io.execute(()->{
            PlatformController.Snapshot next=null;PolicyBook initial=null;String result;boolean success=false;
            try{
                if(controller==null)controller=new PlatformController(this);next=op.execute(controller);
                if(book==null){
                    String saved=getPreferences(0).getString("book3","");
                    PolicyBook candidate=saved.isEmpty()?next.defaults():PolicyBook.parse(saved);
                    for(PlatformProfile p:candidate.presets)next.validate(p);
                    for(PolicyBook.Rule r:candidate.apps.values())if(r.custom!=null)next.validate(r.custom);
                    initial=candidate;
                }
                success=true;result=label.equals("保留本次配置")?"配置已应用":label.equals("同步三预设与分应用策略")?"配置已更新":label.equals("恢复接管前设置")?"已结束接管并恢复":label.equals("撤回本次试用")?"本次试用已撤回":label.startsWith("试用")?"试用已开始":label+"完成";
            }catch(Exception e){result=e.getMessage()==null?e.toString():e.getMessage();if(controller!=null){controller.close();controller=null;}}
            PlatformController.Snapshot read=next;PolicyBook loaded=initial;String info=result;boolean ok=success;
            runOnUiThread(()->{if(isDestroyed())return;if(quiet)reading=false;else busy=false;if(read!=null){long deadline=SystemClock.elapsedRealtime()+read.remaining*1000L;boolean sameTrial=snapshot!=null&&snapshot.phase.equals("trial")&&read.phase.equals("trial")&&snapshot.token.equals(read.token);trialDeadline=sameTrial?Math.min(trialDeadline,deadline):deadline;snapshot=read;}if(loaded!=null){book=loaded;editing=PolicyBook.index(book.global);saveLocal();render();}updateStatus();if(!quiet||!ok){record(info);if(!ok||!label.startsWith("试用"))notifyUser(info,!ok);}});
        });
    }
    private void saveLocal(){if(book==null)return;try{getPreferences(0).edit().putString("book3",book.json().toString()).apply();}catch(Exception e){toast(e.getMessage());}}
    private PlatformProfile form()throws Exception{
        if(snapshot==null||book==null||lower[0]==null)throw new IOException("先读取设备");
        int[] min=new int[3],max=new int[3],masks=new int[4];
        for(int i=0;i<3;i++){min[i]=snapshot.frequencies[i].get(lower[i].getSelectedItemPosition());max[i]=snapshot.frequencies[i].get(upper[i].getSelectedItemPosition());}
        for(int g=0;g<4;g++)for(int c=0;c<8;c++)if(boxes[g][c].isChecked())masks[g]|=1<<c;
        return new PlatformProfile(min,max,snapshot.gpuFrequencies.get(lower[3].getSelectedItemPosition()),snapshot.gpuFrequencies.get(upper[3].getSelectedItemPosition()),masks);
    }
    private PlatformProfile editor(){return editingApp==null?book.presets[editing]:book.apps.get(editingApp).resolve(book);}
    private void storeForm()throws Exception{PlatformProfile p=form();snapshot.validate(p);if(editingApp==null)book.presets[editing]=p;else book.apps.get(editingApp).custom=p;saveLocal();}
    private void saveEditor(){try{storeForm();toast("预设已保存，尚未应用");}catch(Exception e){toast(e.getMessage());}}
    private void tryEditor(){try{storeForm();PlatformProfile p=editor();if(editingApp==null){book.global=PolicyBook.IDS[editing];saveLocal();}run("试用编辑配置",c->c.apply(p));scroller.smoothScrollTo(0,0);}catch(Exception e){toast(e.getMessage());}}
    private PolicyBook frozenBook() throws Exception{return PolicyBook.parse(book.json().toString());}
    private void confirm(){try{String token=snapshot.token;PolicyBook prepared=frozenBook();run("保留本次配置",c->{PlatformController.Snapshot s=c.confirm(token);c.installBook(prepared);return s;});}catch(Exception e){toast(e.getMessage());}}
    private void sync(){try{saveLocal();PolicyBook prepared=frozenBook();run("同步三预设与分应用策略",c->c.reload(prepared));}catch(Exception e){toast(e.getMessage());}}
    private void toggleAuto(){try{boolean enabled=snapshot.auto;PolicyBook prepared=frozenBook();run("更新自动切换",c->enabled?c.stopAuto(prepared):c.startAuto(prepared));}catch(Exception e){toast(e.getMessage());}}
    private void trialGlobal(){try{PlatformProfile p=book.profile(book.global);run("试用全局默认",c->c.apply(p));}catch(Exception e){toast(e.getMessage());}}
    private void trialWindow(){
        boolean pending=snapshot!=null&&snapshot.phase.equals("trial");
        if(!pending){if(trialDialog!=null){trialDialog.dismiss();trialDialog=null;}return;}
        if(!visible)return;
        if(trialDialog==null||!dialogToken.equals(snapshot.token)){
            if(trialDialog!=null)trialDialog.dismiss();dialogToken=snapshot.token;
            LinearLayout panel=column();panel.setPadding(dp(24),dp(8),dp(24),dp(12));trialText=text("",32,GREEN,true);panel.addView(trialText);space(panel,12);panel.addView(text("确认后保留这套配置\n未确认将自动回到上一套确认配置",14,MUTED,false));
            trialDialog=new AlertDialog.Builder(this).setTitle("配置试用中").setView(panel).setPositiveButton("确认应用",null).setNegativeButton("取消",null).create();trialDialog.setCancelable(false);trialDialog.setCanceledOnTouchOutside(false);trialDialog.show();
            flatButton(trialDialog.getButton(AlertDialog.BUTTON_POSITIVE),false,12);flatButton(trialDialog.getButton(AlertDialog.BUTTON_NEGATIVE),false,12);
            trialDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->confirm());
            trialDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{String token=dialogToken;run("撤回本次试用",c->c.rollback(token));});
        }
        long seconds=Math.max(0,(trialDeadline-SystemClock.elapsedRealtime()+999)/1000);
        String countdown=seconds>0?seconds+" 秒":"正在撤回…";
        if(!TextUtils.equals(trialText.getText(),countdown)){trialText.setText(countdown);if(ValueAnimator.areAnimatorsEnabled()){trialText.setAlpha(0.65f);trialText.animate().alpha(1).setDuration(120).start();}}
        trialDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy&&seconds>0);
        trialDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy&&seconds>0);
    }
    private void updateStatus(){
        if(stateText!=null)setTextIfChanged(stateText,configStatus());
        if(takeoverSwitch!=null){updatingSwitch=true;takeoverSwitch.setChecked(snapshot!=null&&!snapshot.phase.equals("none"));takeoverSwitch.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("trial")&&!snapshot.phase.equals("restoring"));updatingSwitch=false;}
        trialWindow();
        if(detailText!=null&&snapshot!=null&&displayedSnapshot!=snapshot){
            StringBuilder out=new StringBuilder("状态：").append(configStatus()).append("\n平台：天玑 9400+（MT6991）\n\n当前设备配置\n");
            try{appendProfile(out,snapshot.current());}catch(Exception e){out.append("设备配置无法读取\n");}
            out.append("\nCPU 调速器\n");for(int i=0;i<3;i++)out.append(cluster(i)).append("  ").append(snapshot.governors[i]).append('\n');
            if(!snapshot.phase.equals("none"))try{out.append("\n已应用的目标\n");appendProfile(out,snapshot.intent());out.append("\n应用来源：").append(snapshot.actor.equals("global")?"全局配置":snapshot.actor.startsWith("auto.")?snapshot.actor.substring(5):snapshot.actor).append('\n');}catch(Exception e){out.append("目标数据无法读取\n");}else out.append("\n已应用的目标：无\n");
            out.append("\n自动切换：").append(snapshot.auto?(snapshot.watch?"运行中":"监听异常"):"已关闭");if(!snapshot.foreground.isEmpty())out.append("\n前台应用：").append(snapshot.foreground);if(!snapshot.watchError.isEmpty())out.append("\n切换提示：").append(snapshot.watchError);
            detailText.setText(out.toString());
            displayedSnapshot=snapshot;
        }
        if(readButton!=null)readButton.setEnabled(!busy);
        if(globalChooser!=null)globalChooser.setEnabled(!busy&&snapshot!=null&&!snapshot.phase.equals("trial")&&!snapshot.phase.equals("restoring")&&!snapshot.phase.equals("restore-failed"));
        if(applyButton!=null)applyButton.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("restoring")&&!snapshot.phase.equals("restore-failed"));
        if(autoButton!=null){autoButton.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("trial"));setTextIfChanged(autoButton,snapshot!=null&&snapshot.auto?"关闭分应用切换，回到全局":"启用分应用自动切换");}
        if(restoreButton!=null)restoreButton.setEnabled(!busy&&snapshot!=null);
        if(syncButton!=null)syncButton.setEnabled(!busy&&book!=null&&snapshot!=null&&!snapshot.phase.equals("trial"));
        handler.removeCallbacks(ticker);
        if(visible&&snapshot!=null&&(snapshot.phase.equals("trial")||snapshot.phase.equals("restoring")||((snapshot.phase.equals("active")||snapshot.phase.equals("legacy"))&&!snapshot.matches&&rechecks<3)))handler.postDelayed(ticker,1000);
    }
    private String configStatus(){
        if(snapshot==null)return busy||reading?"读取设备中":"需要 Root 权限";
        if(snapshot.phase.equals("none"))return "未启用配置";
        if(snapshot.phase.equals("active")||snapshot.phase.equals("legacy")||snapshot.phase.equals("trial"))return snapshot.matches?"配置应用中":"配置被修改";
        return snapshot.status(rechecks);
    }
    private void appendProfile(StringBuilder out,PlatformProfile profile){
        for(int i=0;i<3;i++)out.append(cluster(i)).append("  ").append(mhz(profile.min[i])).append(" – ").append(mhz(profile.max[i])).append('\n');
        out.append("GPU  ").append(mhz(profile.gpuMin)).append(" – ").append(mhz(profile.gpuMax)).append('\n');
        for(int i=0;i<4;i++)out.append(GROUP_LABELS[i]).append("  CPU ").append(cpuList(profile.masks[i])).append('\n');
    }
    private void render(){
        content.removeAllViews();displayedSnapshot=null;stateText=detailText=null;takeoverSwitch=null;globalChooser=null;applyButton=autoButton=restoreButton=syncButton=readButton=null;
        if(tab!=0){Button back=new Button(this);back.setText("‹  返回首页");flatButton(back,false,12);back.setBackgroundColor(0);content.addView(back,new LinearLayout.LayoutParams(-2,dp(42)));back.setOnClickListener(v->{tab=0;editingApp=null;render();});}
        content.addView(text(tab==0?"轻调":tab==1?"自定义配置":tab==2?"分应用策略":tab==3?"系统配置":"日志",28,INK,true));content.addView(text("天玑 9400+",13,MUTED,false));space(content,22);
        if(tab==0)renderHome();else if(tab==4)renderLogs();else if(snapshot==null||book==null){LinearLayout c=card();stateText=text("",16,INK,true);c.addView(stateText);readButton=button(c,"检测",true,()->run("检测",PlatformController::probe));}else if(tab==1)renderEditor();else if(tab==2)renderApps();else renderSystem();updateStatus();scroller.scrollTo(0,0);
        if(renderedTab!=tab){content.animate().cancel();boolean animate=ValueAnimator.areAnimatorsEnabled();content.setAlpha(animate?0:1);content.setTranslationY(animate?dp(8):0);if(animate)content.animate().alpha(1).translationY(0).setDuration(180).start();renderedTab=tab;}
    }
    private void renderHome(){
        LinearLayout takeover=card(),header=row();header.setGravity(Gravity.CENTER_VERTICAL);header.addView(text("接管系统配置",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));takeoverSwitch=new Switch(this);takeoverSwitch.setContentDescription("接管系统配置开关");header.addView(takeoverSwitch);takeover.setPadding(dp(18),dp(14),dp(18),dp(14));takeover.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));takeover.setContentDescription("接管系统配置详情");takeover.setFocusable(true);takeover.setOnClickListener(v->{tab=3;render();run("读取配置与目标值",PlatformController::probe);});
        LinearLayout status=row();status.setGravity(Gravity.CENTER_VERTICAL);stateText=text("",14,MUTED,false);status.addView(stateText,new LinearLayout.LayoutParams(0,-2,1));readButton=new Button(this);readButton.setText("检测");flatButton(readButton,false,12);status.addView(readButton,new LinearLayout.LayoutParams(dp(80),dp(42)));space(takeover,12);takeover.addView(status);
        readButton.setOnClickListener(v->run("检测",PlatformController::probe));
        takeoverSwitch.setOnCheckedChangeListener((v,on)->{if(updatingSwitch)return;if(on)trialGlobal();else run("恢复接管前设置",PlatformController::restore);updateStatus();});
        LinearLayout presets=card();presets.addView(text("自定义配置",18,INK,true));space(presets,16);LinearLayout choices=row();
        for(int i=0;i<3;i++){final int index=i;Button b=new Button(this);b.setText(PolicyBook.NAMES[i]);flatButton(b,false,20);b.setTextSize(15);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);if(i!=2)p.rightMargin=dp(8);choices.addView(b,p);b.setEnabled(book!=null);b.setOnClickListener(v->{editing=index;editingApp=null;tab=1;render();});}presets.addView(choices);space(presets,18);
        LinearLayout global=row();global.setGravity(Gravity.CENTER_VERTICAL);global.addView(text("全局默认：",14,INK,false));if(book!=null){globalChooser=selector(Arrays.asList(PolicyBook.NAMES),PolicyBook.index(book.global));global.addView(globalChooser,new LinearLayout.LayoutParams(0,dp(48),1));globalChooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int pos,long id){if(!book.global.equals(PolicyBook.IDS[pos])){book.global=PolicyBook.IDS[pos];saveLocal();trialGlobal();}}public void onNothingSelected(AdapterView<?>p){}});}else global.addView(text("读取中…",14,MUTED,false));presets.addView(global);
        LinearLayout apps=card();entry(apps,"分应用策略",()->{if(book!=null){tab=2;render();}else toast("请先完成设备检测");});
        LinearLayout logs=card();LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.addView(text("日志",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));Switch collect=new Switch(this);collect.setText("收集");collect.setTextColor(MUTED);collect.setChecked(getPreferences(0).getBoolean("collectLogs",false));line.addView(collect);logs.addView(line);collect.setOnCheckedChangeListener((v,on)->{SharedPreferences.Editor prefs=getPreferences(0).edit().putBoolean("collectLogs",on);if(!on)prefs.remove("logs");prefs.apply();if(on)record("开始收集本应用操作日志");render();notifyUser(on?"日志收集已开启":"日志已关闭并清空",false);});if(collect.isChecked())button(logs,"进入日志",false,()->{tab=4;render();});
    }
    private void entry(LinearLayout card,String label,Runnable action){LinearLayout line=row();line.setPadding(0,dp(6),0,dp(6));line.setGravity(Gravity.CENTER_VERTICAL);line.addView(text(label,18,INK,true),new LinearLayout.LayoutParams(0,-2,1));line.addView(text("›",28,GREEN,false));card.addView(line);card.setContentDescription(label);card.setFocusable(true);pressAnimation(card);card.setOnClickListener(v->action.run());}
    private void renderLogs(){LinearLayout c=card();c.addView(text("仅记录本应用操作与错误\n最多保留 120 条，无后台采样",13,MUTED,false));space(c,16);String log=getPreferences(0).getString("logs","");TextView entries=text(log.isEmpty()?"暂无日志":log,13,INK,false);entries.setTextIsSelectable(true);c.addView(entries);button(c,"清空日志",false,()->{getPreferences(0).edit().remove("logs").apply();render();notifyUser("日志已清空",false);});}
    private void renderEditor(){
        LinearLayout title=card();
        if(editingApp==null)title.addView(text(PolicyBook.NAMES[editing]+"预设",18,INK,true));
        else{title.addView(text("应用独立设置",18,INK,true));title.addView(text(appName(editingApp),12,MUTED,false));}
        title.addView(text("可在已生效配置上继续修改\n新试用未确认时，回到上一套确认配置",12,MUTED,false));
        PlatformProfile p=editor();LinearLayout frequencies=card();frequencies.addView(text("CPU 与 GPU 频率范围",18,INK,true));
        for(int i=0;i<4;i++){
            List<Integer> values=i==3?snapshot.gpuFrequencies:snapshot.frequencies[i];int lo=i==3?p.gpuMin:p.min[i],hi=i==3?p.gpuMax:p.max[i];space(frequencies,12);frequencies.addView(text(i==3?"GPU · 实际驱动档位":cluster(i),14,INK,true));
            LinearLayout line=row(),left=column(),right=column();left.addView(text("最低",12,MUTED,false));right.addView(text("最高",12,MUTED,false));lower[i]=frequencies(values,lo);upper[i]=frequencies(values,hi);left.addView(lower[i]);right.addView(upper[i]);line.addView(left,new LinearLayout.LayoutParams(0,-2,1));line.addView(right,new LinearLayout.LayoutParams(0,-2,1));frequencies.addView(line);
        }
        space(frequencies,10);frequencies.addView(text("列出支持的全部档位，不设置人为下限\n保留 sugov_ext 与温控，不超出设备档位",12,MUTED,false));
        LinearLayout cores=card();cores.addView(text("四类进程绑核",18,INK,true));
        for(int g=0;g<4;g++){space(cores,12);cores.addView(text(GROUP_LABELS[g],14,INK,true));for(int start=0;start<8;start+=4){LinearLayout line=row();for(int c=start;c<start+4;c++){CheckBox b=new CheckBox(this);b.setText("CPU"+c);b.setTextSize(12);b.setTextColor(INK);b.setChecked((p.masks[g]&(1<<c))!=0);boxes[g][c]=b;line.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}cores.addView(line);}}
        cores.addView(text("每组可选 CPU0–7，至少保留一个核心\n这是允许使用的核心，不是关闭其他核心",12,MUTED,false));
        LinearLayout actions=card();button(actions,editingApp==null?"保存这套预设草稿":"保存应用独立设置",false,this::saveEditor);applyButton=button(actions,editingApp==null?"试用并设为全局 · 20 秒":"试用应用独立配置 · 20 秒",true,this::tryEditor);restoreButton=button(actions,"结束接管并恢复",false,()->run("恢复接管前设置",PlatformController::restore));
    }
    private void renderApps(){
        LinearLayout c=card();c.addView(text("分应用策略",18,INK,true));c.addView(text("前台应用使用专属预设或独立设置\n离开后回到全局默认，未匹配应用也用全局默认",12,MUTED,false));
        button(c,"添加已安装应用",true,this::chooseApp);autoButton=button(c,"",true,this::toggleAuto);syncButton=button(c,"同步已保存的策略",false,this::sync);
        if(book.apps.isEmpty())c.addView(text("还没有分应用策略",13,MUTED,false));
        for(Map.Entry<String,PolicyBook.Rule> e:book.apps.entrySet()){
            String pkg=e.getKey();PolicyBook.Rule rule=e.getValue();LinearLayout app=card();app.addView(text(appName(pkg),16,INK,true));app.addView(text(pkg,11,MUTED,false));app.addView(text(!rule.enabled?"已停用":rule.custom!=null?"独立设置":PolicyBook.NAMES[PolicyBook.index(rule.preset)],13,GREEN,true));
            button(app,"更改应用策略",false,()->ruleDialog(pkg));
        }
    }
    private void ruleDialog(String pkg){
        new AlertDialog.Builder(this).setTitle(appName(pkg)).setItems(new String[]{"使用省电预设","使用均衡预设","使用性能预设","编辑独立设置","启用或停用","移除策略"},(d,which)->{
            PolicyBook.Rule r=book.apps.get(pkg);
            if(which<3){r.preset=PolicyBook.IDS[which];r.custom=null;r.enabled=true;saveLocal();render();}
            else if(which==3){editingApp=pkg;tab=1;render();}
            else if(which==4){r.enabled=!r.enabled;saveLocal();render();}
            else{book.apps.remove(pkg);saveLocal();render();}
            if(which!=3)toast("应用策略已保存，尚未同步");
        }).show();
    }
    private String appName(String pkg){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){return pkg;}}
    private void chooseApp(){
        List<ResolveInfo> installed=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);TreeMap<String,String> names=new TreeMap<>();
        for(ResolveInfo r:installed){String pkg=r.activityInfo.packageName;if(PolicyBook.packageAllowed(pkg))names.put(appName(pkg)+"\n"+pkg,pkg);}
        String[] labels=names.keySet().toArray(new String[0]),packages=names.values().toArray(new String[0]);ListView list=new ListView(this);list.setDivider(new ColorDrawable(OUTLINE));list.setDividerHeight(dp(1));
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return packages.length;}public Object getItem(int i){return packages[i];}public long getItemId(int i){return i;}
            public View getView(int i,View reusable,ViewGroup parent){
                LinearLayout line;View[] views;
                if(reusable==null){line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(24),dp(14),dp(24),dp(14));line.setMinimumHeight(dp(80));ImageView icon=new ImageView(MainActivity.this);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(44),dp(44));size.rightMargin=dp(16);line.addView(icon,size);LinearLayout words=column();TextView name=text("",16,INK,true),pkg=text("",12,MUTED,false);name.setMaxLines(2);pkg.setMaxLines(2);words.addView(name);space(words,4);words.addView(pkg);line.addView(words,new LinearLayout.LayoutParams(0,-2,1));views=new View[]{icon,name,pkg};line.setTag(views);}else{line=(LinearLayout)reusable;views=(View[])line.getTag();}
                String name=labels[i].substring(0,labels[i].indexOf('\n'));((TextView)views[1]).setText(name);((TextView)views[2]).setText(packages[i]);try{((ImageView)views[0]).setImageDrawable(getPackageManager().getApplicationIcon(packages[i]));}catch(PackageManager.NameNotFoundException ignored){((ImageView)views[0]).setImageDrawable(getPackageManager().getDefaultActivityIcon());}return line;
            }
        });
        LinearLayout panel=column();panel.addView(list,new LinearLayout.LayoutParams(-1,Math.min(dp(420),getResources().getDisplayMetrics().heightPixels/2)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("添加已安装应用").setView(panel).setNegativeButton("取消",null).create();list.setOnItemClickListener((p,v,i,id)->{dialog.dismiss();book.apps.putIfAbsent(packages[i],new PolicyBook.Rule());saveLocal();render();ruleDialog(packages[i]);});dialog.show();
    }
    private void renderSystem(){
        LinearLayout live=card();live.addView(text("运行配置",18,INK,true));space(live,12);detailText=text("",14,INK,false);detailText.setTextIsSelectable(true);live.addView(detailText);
        LinearLayout global=card();global.addView(text("全局默认",18,INK,true));space(global,10);global.addView(text(PolicyBook.NAMES[PolicyBook.index(book.global)]+"预设",15,GREEN,true));global.addView(text("以下为本地保存的配置，实际应用值见上方",12,MUTED,false));
        for(int i=0;i<3;i++){LinearLayout preset=card();preset.addView(text(PolicyBook.NAMES[i]+"预设",18,INK,true));space(preset,12);preset.addView(profileDetails(book.presets[i]));}
        LinearLayout apps=card();apps.addView(text("分应用配置",18,INK,true));if(book.apps.isEmpty()){space(apps,12);apps.addView(text("暂无分应用策略",14,MUTED,false));}
        for(Map.Entry<String,PolicyBook.Rule> entry:book.apps.entrySet()){String pkg=entry.getKey();PolicyBook.Rule rule=entry.getValue();space(apps,16);View divider=new View(this);divider.setBackgroundColor(OUTLINE);apps.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));space(apps,12);apps.addView(text(appName(pkg),16,INK,true));apps.addView(text(pkg,12,MUTED,false));apps.addView(text((rule.enabled?"已启用":"已停用")+"："+(rule.custom==null?PolicyBook.NAMES[PolicyBook.index(rule.preset)]+"预设":"独立设置"),14,GREEN,false));space(apps,8);apps.addView(profileDetails(rule.resolve(book)));}
    }
    private TextView profileDetails(PlatformProfile profile){StringBuilder values=new StringBuilder();appendProfile(values,profile);TextView details=text(values.toString(),14,INK,false);details.setTextIsSelectable(true);return details;}
    private Spinner selector(List<String> labels,int chosen){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels){@Override public View getView(int p,View v,ViewGroup parent){View result=super.getView(p,v,parent);((TextView)result).setTextColor(INK);return result;}@Override public View getDropDownView(int p,View v,ViewGroup parent){View result=super.getDropDownView(p,v,parent);((TextView)result).setTextColor(INK);result.setBackgroundColor(SURFACE);return result;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setSelection(chosen);return s;}
    private Spinner frequencies(List<Integer> values,int chosen){List<String>labels=new ArrayList<>();for(int n:values)labels.add(mhz(n));return selector(labels,Math.max(0,values.indexOf(chosen)));}
    private static String mhz(int k){return k/1000+" MHz";}
    private static String cpuList(int mask){StringJoiner s=new StringJoiner(", ");for(int i=0;i<8;i++)if((mask&(1<<i))!=0)s.add(Integer.toString(i));return s.toString();}
    private static String cluster(int i){return i==0?"CPU0–3":i==1?"CPU4–6":"CPU7";}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void space(LinearLayout c,int height){c.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(15),dp(15),dp(15),dp(15));c.setBackground(background(SURFACE,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);content.addView(c,p);return c;}
    private void flatButton(Button b,boolean primary,int radius){b.setAllCaps(false);b.setTextSize(14);b.setStateListAnimator(null);b.setElevation(0);b.setTranslationZ(0);b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,primary?ON_PRIMARY:GREEN}));StateListDrawable states=new StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},background(CONTAINER,radius));states.addState(new int[]{},background(primary?GREEN:CONTAINER,radius));b.setBackground(new RippleDrawable(ColorStateList.valueOf((GREEN&0x00ffffff)|0x25000000),states,null));pressAnimation(b);}
    private void setTextIfChanged(TextView view,String value){if(!TextUtils.equals(view.getText(),value))view.setText(value);}
    private void pressAnimation(View view){view.setOnTouchListener((v,event)->{if(ValueAnimator.areAnimatorsEnabled()){int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN&&v.isEnabled())v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)v.animate().scaleX(1).scaleY(1).setDuration(120).start();}return false;});}
    private Button button(LinearLayout c,String label,boolean primary,Runnable action){Button b=new Button(this);b.setText(label);flatButton(b,primary,12);space(c,10);c.addView(b,new LinearLayout.LayoutParams(-1,dp(46)));b.setOnClickListener(v->action.run());return b;}
    private void record(String s){
        if(!getPreferences(0).getBoolean("collectLogs",false))return;
        String line=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.MEDIUM).format(new Date())+"  "+s.replace('\n',' ');
        String log=line+"\n"+getPreferences(0).getString("logs","");String[] entries=log.split("\n");StringBuilder bounded=new StringBuilder();for(int i=0;i<Math.min(120,entries.length);i++){if(bounded.length()+entries[i].length()>16000)break;bounded.append(entries[i]).append('\n');}getPreferences(0).edit().putString("logs",bounded.toString()).apply();
    }
    private void notifyUser(String s,boolean error){
        if(!visible||notice==null)return;handler.removeCallbacks(hideNotice);notice.animate().cancel();notice.animate().withEndAction(null);notice.setText(s==null?"操作失败":s);notice.setTextColor(error?ON_PRIMARY:BG);notice.setBackground(background(error?GREEN:INK,18));boolean animate=ValueAnimator.areAnimatorsEnabled();notice.setAlpha(animate?0:1);notice.setTranslationY(animate?dp(8):0);notice.setVisibility(View.VISIBLE);if(animate)notice.animate().alpha(1).translationY(0).setDuration(160).start();handler.postDelayed(hideNotice,error?4500:2500);
    }
    private void toast(String s){record(s==null?"操作失败":s);notifyUser(s,false);}
}
