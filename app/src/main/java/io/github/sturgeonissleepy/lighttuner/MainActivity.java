package io.github.sturgeonissleepy.lighttuner;

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
    private ThreadRules threadRules=new ThreadRules(),sharedRules=new ThreadRules();
    private final Map<String,String> appLabels=new HashMap<>();
    private Recording recording=new Recording();
    private String selectedApp=null,exportId="";
    private boolean viewingHistory,waitingDocument,waitingOverlayPermission;
    private List<File> histories=new ArrayList<>();
    private Recording.ThreadSample selectedThread;
    private List<float[]> threadPoints=new ArrayList<>();
    private int recordSeconds=60;
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
    private Spinner upResponse,downResponse,topResponse;
    private final List<Integer> responseValues=new ArrayList<>(),boostValues=new ArrayList<>();
    private int selectedFrameLayer=-1;
    private String selectedFrameSession="";
    private CheckBox[][] boxes=new CheckBox[4][8];
    private Button applyButton,autoButton,restoreButton,readButton;
    private int tab=0,renderedTab=-1,editing=1,rechecks=0,ticks=0;
    private String editingApp=null;
    private boolean busy,reading,visible;
    private long trialDeadline;
    private long threadDeadline;
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            if(!visible) return;
            updateStatus();
            if(snapshot!=null && !busy && !reading) {
                boolean pending=snapshot.phase.equals("trial") || snapshot.phase.equals("restoring") || !snapshot.threadToken.isEmpty();
                boolean settling=(snapshot.phase.equals("active") || snapshot.phase.equals("legacy")) && !snapshot.matches && rechecks<3;
                if((pending||settling)&&++ticks%2==0) { if(settling) rechecks++; MainActivity.this.run("自动复核读数",PlatformController::probe); }
            }
        }
    };
    private final Runnable recordTicker=()->{if(!visible||tab!=5||viewingHistory||!recording.running())return;if(!busy&&!reading)run("录制进度",c->{recording=c.readRecording();histories=new SessionArchive(getFilesDir()).list("*");return snapshot;});else updateStatus();};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try{threadRules=ThreadRules.parse(getPreferences(0).getString("threads",""));recording=Recording.read(new File(getFilesDir(),"recording"));}catch(Exception e){record("本地线程或录制数据读取失败："+e.getMessage());}
        if(saved!=null){tab=saved.getInt("tab",0);selectedApp=saved.getString("app");editingApp=saved.getString("editingApp");editing=saved.getInt("editing",1);recordSeconds=saved.getInt("seconds",60);waitingDocument=saved.getBoolean("document");waitingOverlayPermission=saved.getBoolean("overlayPermission");exportId=saved.getString("export","");String id=saved.getString("history","");if(id.matches("[a-f0-9-]{36}"))try{recording=Recording.read(new File(getFilesDir(),"sessions/"+id));viewingHistory=true;}catch(Exception e){tab=10;}if(tab==14)tab=9;}
        BG=getColor(R.color.app_background);INK=getColor(R.color.app_text);GREEN=getColor(R.color.app_primary);MUTED=getColor(R.color.app_muted);SURFACE=getColor(R.color.app_surface);CONTAINER=getColor(R.color.app_container);ON_PRIMARY=getColor(R.color.app_on_primary);OUTLINE=getColor(R.color.app_outline);
        if(!getPreferences(0).getBoolean("collectLogs",false))getPreferences(0).edit().remove("logs").apply();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});
        scroller=new ScrollView(this); content=column(); content.setPadding(dp(20),dp(20),dp(20),dp(24));scroller.addView(content);root.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
        notice=text("",14,BG,false);notice.setPadding(dp(20),dp(13),dp(20),dp(13));notice.setBackground(background(INK,18));notice.setVisibility(View.GONE);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams popup=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);popup.setMargins(dp(24),0,dp(24),dp(28));root.addView(notice,popup);setContentView(root);render();
        String requested=getIntent().getStringExtra("recordApp");if(requested!=null&&PolicyBook.packageAllowed(requested)){selectedApp=requested;tab=5;render();}
        root.post(()->{WindowInsetsController b=getWindow().getInsetsController();boolean night=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;if(b!=null)b.setSystemBarsAppearance(night?0:light,light);});
    }
    @Override public void onResume(){super.onResume();waitingOverlayPermission=false;visible=true;rechecks=0;ticks=0;updateStatus();if(!busy)run("读取配置与目标值",c->{if(!viewingHistory)recording=c.readRecording();histories=new SessionArchive(getFilesDir()).list("*");return c.recover();});}
    @Override public void onPause(){visible=false;handler.removeCallbacks(ticker);handler.removeCallbacks(recordTicker);super.onPause();}
    @Override public void onStop(){super.onStop();if(!isChangingConfigurations()&&!waitingDocument&&!waitingOverlayPermission)finish();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("tab",tab);state.putString("app",selectedApp);state.putString("editingApp",editingApp);state.putInt("editing",editing);state.putInt("seconds",recordSeconds);state.putBoolean("document",waitingDocument);state.putBoolean("overlayPermission",waitingOverlayPermission);state.putString("export",exportId);if(viewingHistory)state.putString("history",recording.id);}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(trialDialog!=null)trialDialog.dismiss();io.execute(()->{if(controller!=null)controller.close();});io.shutdown();super.onDestroy();}
    private int parentTab(){switch(tab){case 10:return 2;case 5:case 6:case 11:return 10;case 9:case 13:return 5;case 14:return 9;case 1:return editingApp==null?0:10;case 4:case 7:case 8:return 12;default:return 0;}}
    private void navigateBack(){if(tab!=0){tab=parentTab();editingApp=null;render();}else finish();}
    @Override public void onBackPressed(){navigateBack();}
    private interface Operation{PlatformController.Snapshot execute(PlatformController c)throws Exception;}
    private void run(String label,Operation op){
        boolean quiet=label.equals("自动复核读数")||label.equals("读取配置与目标值")||label.equals("录制进度");
        if(busy||(quiet&&reading))return;if(quiet)reading=true;else{busy=true;rechecks=0;}updateStatus();
        io.execute(()->{
            PlatformController.Snapshot next=null;PolicyBook initial=null;String result;boolean success=false;
            try{
                if(controller==null)controller=new PlatformController(this);next=op.execute(controller);
                if(book==null){
                    String saved=getPreferences(0).getString("book3","");
                    PolicyBook candidate=saved.isEmpty()?next.defaults():PolicyBook.parse(saved);
                    for(PlatformProfile p:candidate.presets)next.validateSaved(p);
                    for(PolicyBook.Rule r:candidate.apps.values())if(r.custom!=null)next.validateSaved(r.custom);
                    initial=candidate;
                }
                success=true;result=label.equals("保留本次配置")?"配置已应用":label.equals("同步三预设与分应用策略")?"配置已更新":label.equals("恢复接管前设置")?"已结束接管并恢复":label.equals("撤回本次试用")?"本次试用已撤回":label.startsWith("试用")?"试用已开始":label+"完成";
            }catch(Exception e){result=e.getMessage()==null?e.toString():e.getMessage();if(controller!=null){controller.close();controller=null;}}
            PlatformController.Snapshot read=next;PolicyBook loaded=initial;String info=result;boolean ok=success;
            runOnUiThread(()->{if(isDestroyed())return;if(quiet)reading=false;else busy=false;if(read!=null){long deadline=SystemClock.elapsedRealtime()+read.remaining*1000L;boolean sameTrial=snapshot!=null&&snapshot.phase.equals("trial")&&read.phase.equals("trial")&&snapshot.token.equals(read.token);trialDeadline=sameTrial?Math.min(trialDeadline,deadline):deadline;long end=SystemClock.elapsedRealtime()+Math.max(0,read.threadRemaining)*1000L;threadDeadline=snapshot!=null&&!read.threadToken.isEmpty()&&read.threadToken.equals(snapshot.threadToken)?Math.min(threadDeadline,end):end;snapshot=read;reconcileThreads();}if(loaded!=null){book=loaded;if(editingApp==null)editing=PolicyBook.index(book.global);saveLocal();render();}else if(tab>=5&&(!quiet||label.equals("录制进度")))render();if(ok&&label.equals("启动录制")&&visible&&getPreferences(0).getBoolean("overlay",false))RecordingOverlay.show(this);updateStatus();if(!quiet||!ok){record(info);if(!ok||!label.startsWith("试用"))notifyUser(info,!ok);}});
        });
    }
    private void saveLocal(){if(book==null)return;try{getPreferences(0).edit().putString("book3",book.json().toString()).apply();}catch(Exception e){toast(e.getMessage());}}
    private PlatformProfile form()throws Exception{
        if(snapshot==null||book==null||lower[0]==null)throw new IOException("先读取设备");
        int[] min=new int[3],max=new int[3],masks=new int[4];
        for(int i=0;i<3;i++){min[i]=snapshot.frequencies[i].get(lower[i].getSelectedItemPosition());max[i]=snapshot.frequencies[i].get(upper[i].getSelectedItemPosition());}
        for(int g=0;g<4;g++)for(int c=0;c<8;c++)if(boxes[g][c].isChecked())masks[g]|=1<<c;
        return new PlatformProfile(min,max,snapshot.gpuFrequencies.get(lower[3].getSelectedItemPosition()),snapshot.gpuFrequencies.get(upper[3].getSelectedItemPosition()),masks,responseValues.get(upResponse.getSelectedItemPosition()),responseValues.get(downResponse.getSelectedItemPosition()),boostValues.get(topResponse.getSelectedItemPosition()));
    }
    private PlatformProfile editor(){return editingApp==null?book.presets[editing]:book.apps.get(editingApp).resolve(book);}
    private void storeForm()throws Exception{PlatformProfile p=form();snapshot.validate(p);if(editingApp==null)book.presets[editing]=p;else book.apps.get(editingApp).custom=p;saveLocal();}
    private void saveEditor(){try{storeForm();if(editingApp==null)toast("预设草稿已保存");else sync();}catch(Exception e){toast(e.getMessage());}}
    private void tryEditor(){try{storeForm();PlatformProfile p=editor();if(editingApp==null){book.global=PolicyBook.IDS[editing];saveLocal();}run("试用编辑配置",c->c.apply(p));scroller.smoothScrollTo(0,0);}catch(Exception e){toast(e.getMessage());}}
    private PolicyBook frozenBook() throws Exception{return PolicyBook.parse(book.json().toString());}
    private void confirm(){try{String token=snapshot.token;PolicyBook prepared=frozenBook();run("保留本次配置",c->{PlatformController.Snapshot s=c.confirm(token);c.installBook(prepared);return s;});}catch(Exception e){toast(e.getMessage());}}
    private void sync(){try{saveLocal();PolicyBook prepared=frozenBook();run("同步三预设与分应用策略",c->c.reload(prepared));}catch(Exception e){toast(e.getMessage());}}
    private void toggleAuto(){try{boolean enabled=snapshot.auto;PolicyBook prepared=frozenBook();run("更新自动切换",c->enabled?c.stopAuto(prepared):c.startAuto(prepared));}catch(Exception e){toast(e.getMessage());}}
    private void trialGlobal(){try{PlatformProfile p=book.profile(book.global);run("试用全局默认",c->c.apply(p));}catch(Exception e){toast(e.getMessage());}}
    private void trialWindow(){
        boolean threadTrial=snapshot!=null&&!snapshot.threadToken.isEmpty();
        if(threadTrial){threadTrialWindow();return;}
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
        countdownText(countdown);
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
            out.append("\n实际升频 / 降频响应（μs）\n");for(int i=0;i<3&&snapshot.tuningActual.length>=7;i++)out.append(cluster(i)).append("  ").append(snapshot.tuningActual[i*2]).append(" / ").append(snapshot.tuningActual[i*2+1]).append('\n');if(snapshot.tuningActual.length>=7)out.append("顶层 uclamp.min：").append(snapshot.tuningActual[6]).append("%\n");if(!snapshot.phase.equals("none"))try{out.append("\n已应用的目标\n");appendProfile(out,snapshot.intent());out.append("\n应用来源：").append(snapshot.actor.equals("global")?"全局配置":snapshot.actor.equals("auto.window")?"小窗合并策略":snapshot.actor.startsWith("auto.")?snapshot.actor.substring(5):snapshot.actor).append('\n');}catch(Exception e){out.append("目标数据无法读取\n");}else out.append("\n已应用的目标：无\n");
            out.append("\n自动切换：").append(snapshot.auto?(snapshot.watch?"运行中":"监听异常"):"已关闭");if(!snapshot.foreground.isEmpty())out.append("\n前台应用：").append(snapshot.foreground);if(!snapshot.watchError.isEmpty())out.append("\n切换提示：").append(snapshot.watchError);
            out.append("\n重启恢复：").append(snapshot.persistent?"已开启":"未开启").append("\n后台守护：").append(snapshot.guardian?"运行中":"未运行").append("\n窗口场景：").append(snapshot.windowMode.equals("floating")?"小窗／迷你窗":"普通窗口");
            detailText.setText(out.toString());
            displayedSnapshot=snapshot;
        }
        if(readButton!=null)readButton.setEnabled(!busy);
        if(globalChooser!=null)globalChooser.setEnabled(!busy&&snapshot!=null&&!snapshot.phase.equals("trial")&&!snapshot.phase.equals("restoring")&&!snapshot.phase.equals("restore-failed"));
        if(applyButton!=null)applyButton.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("restoring")&&!snapshot.phase.equals("restore-failed"));
        if(autoButton!=null){autoButton.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("trial"));setTextIfChanged(autoButton,snapshot!=null&&snapshot.auto?"关闭分应用切换，回到全局":"启用分应用自动切换");}
        if(restoreButton!=null)restoreButton.setEnabled(!busy&&snapshot!=null);
        handler.removeCallbacks(ticker);
        if(visible&&snapshot!=null&&(snapshot.phase.equals("trial")||snapshot.phase.equals("restoring")||!snapshot.threadToken.isEmpty()||((snapshot.phase.equals("active")||snapshot.phase.equals("legacy"))&&!snapshot.matches&&rechecks<3)))handler.postDelayed(ticker,1000);
        handler.removeCallbacks(recordTicker);if(visible&&tab==5&&!viewingHistory&&recording.running())handler.postDelayed(recordTicker,5000);
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
        for(int i=0;i<4;i++)out.append(GROUP_LABELS[i]).append("  CPU ").append(cpuList(profile.masks[i])).append('\n');out.append("升频响应：").append(profile.upRateUs<0?"跟随系统":profile.upRateUs+" μs").append("\n降频响应：").append(profile.downRateUs<0?"跟随系统":profile.downRateUs+" μs").append("\n顶层利用率下限：").append(profile.topAppMin<0?"跟随系统":profile.topAppMin+"%").append('\n');
    }
    private void render(){
        content.removeAllViews();displayedSnapshot=null;stateText=detailText=null;takeoverSwitch=null;globalChooser=null;applyButton=autoButton=restoreButton=readButton=null;
        if(tab!=0){Button back=new Button(this);back.setText("‹  返回"+(parentTab()==0?"首页":parentTab()==10?"应用详情":parentTab()==2?"应用列表":parentTab()==5?"性能记录":parentTab()==9?"线程列表":"设置"));flatButton(back,false,12);back.setBackgroundColor(0);content.addView(back,new LinearLayout.LayoutParams(-2,dp(42)));back.setOnClickListener(v->navigateBack());}
        content.addView(text(new String[]{"轻调","自定义配置","应用","系统配置","日志","性能记录","线程策略","共享策略","后台恢复与小窗","线程分析","应用详情","历史记录","设置","候选建议","线程详情"}[tab],28,INK,true));content.addView(text(selectedApp!=null&&Arrays.asList(5,6,9,10,11,13,14).contains(tab)?appName(selectedApp):"天玑 9400+",13,MUTED,false));space(content,22);
        if(tab==0)renderHome();else if(tab==4)renderLogs();else if(tab==12)renderSettings();else if(snapshot==null||book==null){LinearLayout c=card();stateText=text("",16,INK,true);c.addView(stateText);readButton=button(c,"检测",true,()->run("检测",PlatformController::probe));}else switch(tab){case 1:renderEditor();break;case 2:renderApps();break;case 3:renderSystem();break;case 5:renderRecording();break;case 6:renderThreads();break;case 7:renderCloud();break;case 8:renderRecovery();break;case 9:renderRecordedThreads();break;case 10:renderAppDetail();break;case 11:renderHistory();break;case 13:renderCandidates();break;case 14:renderThreadDetail();break;}updateStatus();scroller.scrollTo(0,0);
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
        LinearLayout apps=card();entry(apps,"应用与性能记录",()->{if(book!=null){selectedApp=null;tab=2;render();}else toast("请先完成设备检测");});apps.addView(text("选择应用，录制分析，再试用线程策略",12,MUTED,false));
        LinearLayout settings=card();entry(settings,"设置",()->{tab=12;render();});
    }
    private void renderSettings(){
        LinearLayout cloudEntry=card();entry(cloudEntry,"共享策略",()->{tab=7;render();});
        LinearLayout recoveryEntry=card();entry(recoveryEntry,"后台恢复与小窗",()->{tab=8;render();});
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
        LinearLayout response=card();response.addView(text("调度响应",18,INK,true));response.addView(text("sugov_ext 升降频响应和顶层进程利用率下限\n跟随系统时撤回本项目此前的参数修改",12,MUTED,false));responseValues.clear();TreeSet<Integer> rates=new TreeSet<>(Arrays.asList(-1,0,1000,2000,4000,8000,16000,32000,64000,100000,250000,500000,1000000));rates.add(p.upRateUs);rates.add(p.downRateUs);responseValues.addAll(rates);List<String> labels=new ArrayList<>();for(int n:responseValues)labels.add(n<0?"跟随系统":n+" μs");response.addView(text("升频响应，越短升频越快",13,INK,true));upResponse=selector(labels,responseValues.indexOf(p.upRateUs));response.addView(upResponse);response.addView(text("降频响应，越长越晚降频",13,INK,true));downResponse=selector(labels,responseValues.indexOf(p.downRateUs));response.addView(downResponse);boostValues.clear();boostValues.add(-1);for(int n=0;n<=80;n+=5)boostValues.add(n);if(!boostValues.contains(p.topAppMin))boostValues.add(p.topAppMin);Collections.sort(boostValues);List<String> boosts=new ArrayList<>();for(int n:boostValues)boosts.add(n<0?"跟随系统":n+"%");response.addView(text("顶层利用率下限，越高性能倾向越强",13,INK,true));topResponse=selector(boosts,boostValues.indexOf(p.topAppMin));response.addView(topResponse);response.addView(text("不替换调速器，不添加持续调频循环\n高下限可能增加耗电，仍受温控和频率范围约束\n无对应接口时保持跟随系统",12,MUTED,false));button(response,"填入响应参考值",false,()->{int choice=editingApp==null?editing:PolicyBook.index(book.global);int up=new int[]{8000,2000,1000}[choice],down=new int[]{4000,16000,32000}[choice],boost=new int[]{0,5,15}[choice];boolean supported=snapshot.tuningActual.length==7;for(int i=0;i<6&&supported;i++)supported=!snapshot.tuningActual[i].equals("-");upResponse.setSelection(responseValues.indexOf(supported?up:-1));downResponse.setSelection(responseValues.indexOf(supported?down:-1));topResponse.setSelection(boostValues.indexOf(snapshot.tuningActual.length==7&&!snapshot.tuningActual[6].equals("-")?boost:-1));toast("参考值已填入，先查看参数，再试用确认");});
        LinearLayout actions=card();button(actions,editingApp==null?"保存这套预设草稿":"保存应用独立设置",false,this::saveEditor);applyButton=button(actions,editingApp==null?"试用并设为全局 · 20 秒":"试用应用独立配置 · 20 秒",true,this::tryEditor);restoreButton=button(actions,"结束接管并恢复",false,()->run("恢复接管前设置",PlatformController::restore));
    }
    private void renderApps(){
        LinearLayout c=card();c.addView(text("应用配置与性能记录",18,INK,true));c.addView(text("选择应用 → 录制 → 分析线程 → 试用策略",12,MUTED,false));
        autoButton=button(c,"",true,this::toggleAuto);
        TreeMap<String,String> installed=new TreeMap<>();for(ResolveInfo info:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){String pkg=info.activityInfo.packageName;if(PolicyBook.packageAllowed(pkg))installed.put(appName(pkg)+"\n"+pkg,pkg);}
        for(String pkg:book.apps.keySet())installed.put(appName(pkg)+"\n"+pkg,pkg);for(ThreadRules.Rule rule:threadRules.entries)installed.put(appName(rule.packageName)+"\n"+rule.packageName,rule.packageName);
        List<String> all=new ArrayList<>(installed.values()),shown=new ArrayList<>(all);EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(INK);search.setHint("搜索应用名称或包名");c.addView(search);
        ListView list=new ListView(this);list.setDivider(new ColorDrawable(OUTLINE));list.setDividerHeight(dp(1));BaseAdapter adapter=new BaseAdapter(){public int getCount(){return shown.size();}public Object getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}public View getView(int i,View reused,ViewGroup parent){return appRow(shown.get(i),reused,appPolicy(shown.get(i)));}};list.setAdapter(adapter);
        c.addView(list,new LinearLayout.LayoutParams(-1,getResources().getDisplayMetrics().heightPixels*2/3));nestedList(list);list.setOnItemClickListener((p,v,i,id)->openApp(shown.get(i)));
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){String term=s.toString().toLowerCase(Locale.ROOT);shown.clear();for(String pkg:all)if(pkg.toLowerCase(Locale.ROOT).contains(term)||appName(pkg).toLowerCase(Locale.ROOT).contains(term))shown.add(pkg);adapter.notifyDataSetChanged();}public void afterTextChanged(android.text.Editable e){}});
        button(c,"选择其他已安装应用",false,()->choosePackage(this::openApp));
    }
    private View appRow(String pkg,View reused,String subtitle){
        LinearLayout line;View[] views;if(reused==null){line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(8),dp(14),dp(8),dp(14));ImageView icon=new ImageView(this);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(44),dp(44));size.rightMargin=dp(14);line.addView(icon,size);LinearLayout words=column();TextView name=text("",16,INK,true),description=text("",12,MUTED,false);words.addView(name);words.addView(description);line.addView(words,new LinearLayout.LayoutParams(0,-2,1));views=new View[]{icon,name,description};line.setTag(views);}else{line=(LinearLayout)reused;views=(View[])line.getTag();}((TextView)views[1]).setText(appName(pkg));((TextView)views[2]).setText(subtitle);try{((ImageView)views[0]).setImageDrawable(getPackageManager().getApplicationIcon(pkg));}catch(PackageManager.NameNotFoundException ignored){((ImageView)views[0]).setImageDrawable(getPackageManager().getDefaultActivityIcon());}return line;
    }
    private String appPolicy(String pkg){PolicyBook.Rule rule=book.apps.get(pkg);String config=rule==null||!rule.enabled?"跟随全局默认":rule.custom!=null?"独立设置":PolicyBook.NAMES[PolicyBook.index(rule.preset)]+"预设";long count=threadRules.entries.stream().filter(r->r.packageName.equals(pkg)).count();return config+(count>0?"，"+count+" 条线程规则":"")+"\n"+pkg;}
    private void openApp(String pkg){selectedApp=pkg;editingApp=null;viewingHistory=false;tab=10;render();}
    private void renderAppDetail(){
        LinearLayout heading=card();heading.addView(appRow(selectedApp,null,appPolicy(selectedApp)));heading.addView(text("应用配置与线程策略分别保存，线程规则匹配当前应用版本\n分应用自动切换："+(snapshot.auto?"已开启":"已关闭，应用频率配置暂不切换"),12,MUTED,false));button(heading,"更改应用配置",false,()->ruleDialog(selectedApp));
        LinearLayout recordingEntry=card();entry(recordingEntry,"性能记录",()->{viewingHistory=false;tab=5;render();refreshRecording();});recordingEntry.addView(text("限时采样 CPU、GPU 和应用各进程线程",12,MUTED,false));
        LinearLayout threads=card();entry(threads,"线程策略",()->{tab=6;render();});
        LinearLayout history=card();entry(history,"历史记录",()->{tab=11;render();});history.addView(text("本机最多保留最近 12 次记录，可导出 AI 分析包",12,MUTED,false));
    }
    private void nestedList(ListView list){list.setOnTouchListener((v,event)->{v.getParent().requestDisallowInterceptTouchEvent(event.getActionMasked()!=MotionEvent.ACTION_UP&&event.getActionMasked()!=MotionEvent.ACTION_CANCEL);return false;});}

    private void ruleDialog(String pkg){
        new AlertDialog.Builder(this).setTitle(appName(pkg)).setItems(new String[]{"使用省电预设","使用均衡预设","使用性能预设","编辑独立设置","启用或停用","移除策略"},(d,which)->{
            PolicyBook.Rule r=book.apps.computeIfAbsent(pkg,k->new PolicyBook.Rule());
            if(which<3){r.preset=PolicyBook.IDS[which];r.custom=null;r.enabled=true;saveLocal();render();}
            else if(which==3){r.enabled=true;editingApp=pkg;tab=1;render();}
            else if(which==4){r.enabled=!r.enabled;saveLocal();render();}
            else{book.apps.remove(pkg);saveLocal();render();}
            if(which!=3)sync();
        }).show();
    }
    private String appName(String pkg){if(pkg.equals("unassigned"))return "系统进程／未确定应用归属";String cached=appLabels.get(pkg);if(cached!=null)return cached;String name=pkg;try{name=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(PackageManager.NameNotFoundException ignored){}appLabels.put(pkg,name);return name;}
    private void choosePackage(java.util.function.Consumer<String> selected){
        TreeMap<String,String> names=new TreeMap<>();
        for(ApplicationInfo info:getPackageManager().getInstalledApplications(0)){String pkg=info.packageName;if(PolicyBook.packageAllowed(pkg))names.put(appName(pkg)+"\n"+pkg,pkg);}
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
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择已安装应用").setView(panel).setNegativeButton("取消",null).create();list.setOnItemClickListener((p,v,i,id)->{dialog.dismiss();selected.accept(packages[i]);});dialog.show();
    }
    private void refreshRecording(){run("读取录制",c->{recording=c.readRecording();histories=new SessionArchive(getFilesDir()).list("*");return c.probe();});}
    private void renderRecording(){
        boolean running=recording.running(),belongs=recording.packageName().equals(selectedApp)||recording.packageName().equals("*")&&recording.threads.stream().anyMatch(t->t.packageName.equals(selectedApp));
        LinearLayout c=card();c.addView(text(viewingHistory?"历史性能记录":"记录应用性能",18,INK,true));
        if(!viewingHistory){c.addView(text("每秒采样一次，退出界面后继续\n录制结束即停止，不改变现有调度配置\n应用窗口帧、频率、线程与电池共用时间轴",12,MUTED,false));Spinner duration=selector(Arrays.asList("15 秒","30 秒","60 秒","120 秒","300 秒","600 秒"),Arrays.asList(15,30,60,120,300,600).indexOf(recordSeconds));c.addView(duration);CheckBox overlay=new CheckBox(this);overlay.setText("录制时显示悬浮窗");overlay.setTextColor(INK);overlay.setChecked(getPreferences(0).getBoolean("overlay",false));c.addView(overlay);overlay.setOnCheckedChangeListener((v,on)->{getPreferences(0).edit().putBoolean("overlay",on).apply();if(on&&!android.provider.Settings.canDrawOverlays(this)){waitingOverlayPermission=true;startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName())));}});duration.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int i,long id){recordSeconds=new int[]{15,30,60,120,300,600}[i];}public void onNothingSelected(AdapterView<?>p){}});
            button(c,running?recording.phase.equals("processing")?"正在整理帧数据":"录制进行中":"开始录制",true,()->{int seconds=recordSeconds;String pkg=selectedApp;run("启动录制",root->{root.readRecording();root.record(seconds,pkg);recording=root.readRecording();histories=new SessionArchive(getFilesDir()).list("*");return root.probe();});}).setEnabled(!busy&&!running);
            if(running){if(android.provider.Settings.canDrawOverlays(this))button(c,"显示录制悬浮窗",false,()->RecordingOverlay.show(this));c.addView(text("正在录制："+appName(recording.packageName()),13,GREEN,true));button(c,"停止并保存",false,()->run("停止录制",root->{root.stopRecord();recording=root.readRecording();histories=new SessionArchive(getFilesDir()).list("*");return root.probe();})).setEnabled(!busy);}button(c,"读取最新结果",false,this::refreshRecording).setEnabled(!busy);
        }
        if(recording.phase.equals("none")||!belongs){if(!running)c.addView(text("此应用还没有最近记录，可以先录制或查看历史",13,MUTED,false));return;}
        LinearLayout status=card();status.addView(text(running?"录制进行中":recording.phase.equals("interrupted")?"记录已中断，保留已采样部分":"记录结果",18,INK,true));status.addView(text(String.format(Locale.ROOT,"已采样 %.1f 秒\n观察到 %d 个线程实例",recording.elapsedMs/1000.0,recording.count),14,INK,false));if(recording.capped)status.addView(text("已到 8192 个线程实例上限，后续新线程未收录",12,MUTED,false));
        renderPerformance();
        if(!recording.frequencyRows.isEmpty()){LinearLayout chart=card();chart.addView(text("CPU 与 GPU 实际频率",18,INK,true));chart.addView(new FrequencyChart(this,recording),new LinearLayout.LayoutParams(-1,dp(235)));chart.addView(text("瞬时采样，缺失值留空，不代表精确频率驻留",12,MUTED,false));button(chart,"完整频率表",false,this::frequencyTable);}
        button(status,"分析应用线程",true,()->{tab=9;render();}).setEnabled(!recording.threads.isEmpty()&&!running);button(status,"查看候选建议",false,()->{tab=13;render();}).setEnabled(!recording.threads.isEmpty()&&!running);button(status,"导出 AI 分析包",false,this::exportSession).setEnabled(!busy&&!running&&recording.summaryMs>=1000);
        status.addView(text("先判断线程用途，再试用候选策略\nCPU 高不代表空转，主线程和渲染线程不自动推荐限制",12,MUTED,false));
    }

    private FrameData.Layer frameLayer(){if(!selectedFrameSession.equals(recording.id)){selectedFrameSession=recording.id;selectedFrameLayer=-1;}for(FrameData.Layer l:recording.frames.layers)if(l.id==selectedFrameLayer)return l;return recording.frames.primary();}
    private void renderPerformance(){
        LinearLayout c=card();c.addView(text("帧率与电池",18,INK,true));FrameData.Layer current=frameLayer();if(recording.frames.layers.size()>0){List<String> labels=new ArrayList<>();int selected=0;for(int i=0;i<recording.frames.layers.size();i++){FrameData.Layer l=recording.frames.layers.get(i);labels.add(l.name);if(current!=null&&l.id==current.id)selected=i;}Spinner layer=selector(labels,selected);c.addView(layer);layer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int i,long id){int next=recording.frames.layers.get(i).id;if(frameLayer()==null||frameLayer().id!=next){selectedFrameLayer=next;render();}}public void onNothingSelected(AdapterView<?> p){}});}
        c.addView(new PerformanceChart(this,recording,current,this::inspectSecond),new LinearLayout.LayoutParams(-1,dp(280)));if(current==null)c.addView(text("未读取到目标应用有效呈现帧，帧率保持缺失\n可以打开目标应用进行操作后再读取",13,MUTED,false));else{c.addView(text(String.format(Locale.ROOT,"活动区间平均 %s FPS\nP95 / P99 呈现间隔 %s / %s ms\n卡顿比例 %s，丢弃 %d 帧",metric(current.average),metric(current.p95Interval),metric(current.p99Interval),Double.isFinite(current.jankPercent())?String.format(Locale.ROOT,"%.1f%%",current.jankPercent()):"未知",current.dropped),14,INK,true));c.addView(text(current.source.equals("frametimeline")?"来源：FrameTimeline，按应用进程与窗口归属\n呈现时间使用对应显示帧，应用帧时间线时长另行统计":"来源：SurfaceFlinger 窗口呈现时间\n此接口没有系统卡顿分类和应用帧时间线时长",12,MUTED,false));}
        double power=0,temperature=Double.NaN;int n=0,charging=0;for(double[] point:recording.metrics){if(point[4]==1)charging++;if(point[4]==0&&Double.isFinite(point[1])&&Double.isFinite(point[2])){power+=Math.abs(point[1]*point[2]/1e12);n++;}if(Double.isFinite(point[3]))temperature=Double.isFinite(temperature)?Math.max(temperature,point[3]/10):point[3]/10;}c.addView(text("非充电样本的电池侧平均功率："+(n>0?String.format(Locale.ROOT,"%.2f W",power/n):"缺失")+"\n最高电池温度："+(Double.isFinite(temperature)?String.format(Locale.ROOT,"%.1f℃",temperature):"缺失")+(charging>0?"\n包含充电样本，充电样本不计入耗电平均":""),13,INK,false));c.addView(text("每秒呈现帧数按单个窗口计算，避免多窗口重复累加\n零帧可能是静止或后台状态，不能据此判定卡顿\n电池侧功率由电流与电压估算，帧率和线程同时查看",12,MUTED,false));if(recording.frames.loss||recording.frames.capped)c.addView(text("帧数据存在缺失或达到记录上限，仅供部分分析",13,GREEN,true));
    }
    private static String metric(double value){return Double.isFinite(value)&&value>=0?String.format(Locale.ROOT,"%.1f",value):"—";}
    private void inspectSecond(int second){FrameData.Layer layer=frameLayer();Recording session=recording;localWork("",()->{StringBuilder out=new StringBuilder(second+"–"+(second+1)+" 秒\n");if(layer!=null){out.append("呈现 ").append(layer.bins[second]).append(" 帧\n");List<double[]> rows=FrameData.rows(session.directory,layer.id,second);for(int i=0;i<Math.min(20,rows.size());i++){double[] p=rows.get(i);out.append(String.format(Locale.ROOT,"%.1f ms  间隔 %s ms  时间线 %s ms  jank %d\n",p[0],metric(p[6]),metric(p[7]),(int)p[9]));}if(rows.size()>20)out.append("其余帧见导出 frames.csv\n");}out.append("\n同一秒附近线程占用\n");List<String> samples=new ArrayList<>();File file=new File(session.directory,"record.timeline.csv");if(file.isFile())try(BufferedReader in=new BufferedReader(new FileReader(file))){in.readLine();String line;while((line=in.readLine())!=null){String[] v=line.split(",");if(v.length!=7)continue;double at=Double.parseDouble(v[0]);if(at<second*1000||at>=(second+1)*1000)continue;for(Recording.ThreadSample t:session.threads)if(v[1].equals(Integer.toString(t.pid))&&v[2].equals(Integer.toString(t.tid))&&v[3].equals(t.start)){samples.add(String.format(Locale.ROOT,"%07.2f",Double.parseDouble(v[4]))+"  "+t.name+"\n"+t.process);break;}}}samples.sort(Comparator.reverseOrder());for(int i=0;i<Math.min(10,samples.size());i++)out.append(samples.get(i)).append('\n');out.append("\n线程为一秒区间采样，不能证明单帧卡顿原因");String detail=out.toString();runOnUiThread(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle("时间点详情").setMessage(detail).setPositiveButton("关闭",null).show();});},()->{});}
    private void frequencyTable(){StringBuilder out=new StringBuilder("秒    CPU0–3    CPU4–6    CPU7    GPU（MHz）\n");for(String row:recording.frequencyRows){String[] v=row.split(",");if(v.length!=5)continue;try{out.append(String.format(Locale.ROOT,"%.1f",Double.parseDouble(v[0])/1000));for(int i=1;i<5;i++){int n=Integer.parseInt(v[i]);out.append("    ").append(n>0?Integer.toString(n/1000):"—");}out.append('\n');}catch(NumberFormatException ignored){}}ScrollView scroll=new ScrollView(this);TextView table=text(out.toString(),12,INK,false);table.setPadding(dp(20),dp(12),dp(20),dp(12));table.setTextIsSelectable(true);scroll.addView(table);new AlertDialog.Builder(this).setTitle("完整采样频率").setView(scroll).setPositiveButton("关闭",null).show();}
    private void renderRecordedThreads(){
        LinearLayout c=card();c.addView(text("按平均 CPU 占用排序，100% 代表一个核心\n线程自动归属应用及进程，点开查看时间线与策略\n平均值使用整场统计时长，不是线程存活时长",12,MUTED,false));
        List<Recording.ThreadSample> threads=new ArrayList<>();for(Recording.ThreadSample t:recording.threads)if(t.packageName.equals(selectedApp))threads.add(t);
        ListView list=new ListView(this);list.setDivider(new ColorDrawable(OUTLINE));list.setDividerHeight(dp(1));list.setAdapter(new BaseAdapter(){public int getCount(){return threads.size();}public Object getItem(int i){return threads.get(i);}public long getItemId(int i){return i;}public View getView(int i,View reused,ViewGroup parent){LinearLayout row;TextView title,body;if(reused==null){row=column();row.setPadding(dp(12),dp(14),dp(12),dp(14));title=text("",16,INK,true);body=text("",12,MUTED,false);row.addView(title);row.addView(body);row.setTag(new TextView[]{title,body});}else{row=(LinearLayout)reused;TextView[] views=(TextView[])row.getTag();title=views[0];body=views[1];}Recording.ThreadSample t=threads.get(i);title.setText(t.name+"  "+String.format(Locale.ROOT,"%.2f%%",t.average));body.setText(t.process+"\n最高 "+String.format(Locale.ROOT,"%.2f%%",t.peak)+"，"+(Recording.critical(t)?"关键线程，谨慎调整":recording.candidateReason(t).isEmpty()?"暂无自动建议":"可查看候选建议"));return row;}});c.addView(list,new LinearLayout.LayoutParams(-1,getResources().getDisplayMetrics().heightPixels*2/3));nestedList(list);list.setOnItemClickListener((p,v,i,id)->openThread(threads.get(i)));
    }
    private interface LocalWork{void execute()throws Exception;}
    private void localWork(String label,LocalWork work,Runnable after){if(busy)return;busy=true;updateStatus();io.execute(()->{String error=null;try{work.execute();}catch(Exception e){error=e.getMessage();}String message=error;runOnUiThread(()->{if(isDestroyed())return;busy=false;if(message==null){after.run();if(!label.isEmpty())toast(label);}else notifyUser(message,true);updateStatus();});});}
    private void openThread(Recording.ThreadSample thread){Recording session=recording;localWork("",()->threadPoints=ThreadChart.read(session,thread),()->{selectedThread=thread;tab=14;render();});}
    private void renderThreadDetail(){
        Recording.ThreadSample t=selectedThread;if(t==null){tab=9;render();return;}LinearLayout c=card();c.addView(text(t.name,20,INK,true));c.addView(text(t.process+"\n版本 "+t.versionCode+"\nPID "+t.pid+"，TID "+t.tid+"\n允许核心："+cpuList(t.mask)+String.format(Locale.ROOT,"\n平均 %.2f%%，最高 %.2f%%\n观察到 CPU 时间 %.0f 毫秒",t.average,t.peak,t.average*recording.summaryMs/100),13,MUTED,false));if(Recording.critical(t))c.addView(text("可能影响界面、渲染或实时响应，不自动推荐限制",14,GREEN,true));
        LinearLayout chart=card();chart.addView(text("线程 CPU 占用时间线",18,INK,true));chart.addView(new ThreadChart(this,recording,threadPoints,this::showSample),new LinearLayout.LayoutParams(-1,dp(190)));chart.addView(text("点击区间查看同期 CPU 与 GPU 频率\n只绘制有效增量，缺失区间留空",12,MUTED,false));String reason=recording.candidateReason(t);if(!reason.isEmpty()){chart.addView(text(reason,14,GREEN,true));button(chart,"查看并调整候选策略",true,()->candidateDialog(t));}button(c,"手动设置线程核心",false,()->threadDialog(t.packageName,t.versionCode,t.name,t.mask==0?255:t.mask));
    }
    private void showSample(float[] point){
        StringBuilder out=new StringBuilder(String.format(Locale.ROOT,"采样终点 %.1f 秒\n线程 CPU %.2f%%\n观测间隔 %.0f 毫秒\n最近运行核心：",point[0]/1000,point[1],point[2])).append(point[3]>=0&&point[3]<8?"CPU"+(int)point[3]:"未知");String[] closest=null;double distance=Double.MAX_VALUE;for(String row:recording.frequencyRows){String[] v=row.split(",");if(v.length!=5)continue;try{double d=Math.abs(Double.parseDouble(v[0])-point[0]);if(d<distance){distance=d;closest=v;}}catch(NumberFormatException ignored){}}if(closest!=null&&distance<=1500){out.append("\n\n附近的瞬时频率\n");for(int i=1;i<5;i++)try{int value=Integer.parseInt(closest[i]);out.append(i==4?"GPU":cluster(i-1)).append("：").append(value>0?mhz(value):"缺失").append('\n');}catch(NumberFormatException ignored){}}out.append("\n最近核心不等于整个区间运行核心\n附近频率不代表线程精确频率暴露");new AlertDialog.Builder(this).setTitle("采样详情").setMessage(out.toString()).setPositiveButton("关闭",null).show();
    }
    private void renderCandidates(){
        LinearLayout c=card();c.addView(text("候选建议，需要你判断用途",18,INK,true));c.addView(text("有效统计至少 15 秒、至少 10 次观测且平均占用 ≥30%\n已识别的主线程、渲染、音频等关键线程排除\n建议只针对本次线程名和版本，默认不生成通配规则\n未经真实帧率和耗电对比，不能认定省电有效",12,MUTED,false));int count=0;for(Recording.ThreadSample t:recording.threads){String reason=recording.candidateReason(t);if(!t.packageName.equals(selectedApp)||reason.isEmpty())continue;count++;LinearLayout item=card();item.addView(text(t.name+String.format(Locale.ROOT,"  %.1f%%",t.average),17,INK,true));item.addView(text(t.process+"\n"+reason,12,MUTED,false));button(item,"查看时间线",false,()->openThread(t));button(item,"查看并调整候选策略",true,()->candidateDialog(t));}if(count==0)c.addView(text("本次没有符合条件的候选线程，仍可手动分析",14,INK,false));
    }
    private void candidateDialog(Recording.ThreadSample t){int mask=t.packageName.equals("com.up366.mobile")&&t.name.matches("Thread-[0-9]+")?15:127;new AlertDialog.Builder(this).setTitle("候选策略 · "+t.name).setMessage(recording.candidateReason(t)+"\n\n建议允许 CPU "+cpuList(mask)+"\n仅匹配版本 "+t.versionCode+" 的同名线程\n不限制整个应用、不改主线程或 RenderThread\n下一步可调整核心，试用后再确认").setPositiveButton("调整并试用",(d,i)->threadDialog(t.packageName,t.versionCode,t.name,mask)).setNegativeButton("取消",null).show();}
    private void renderHistory(){
        LinearLayout c=card();c.addView(text("最多保留最近 12 次记录，自动清理更早记录\n在手机本地保存，导出和上传由你选择",12,MUTED,false));int count=0;for(File directory:histories){try{org.json.JSONObject meta=SessionArchive.metadata(directory);if(!meta.optString("packageName","*").equals(selectedApp))continue;count++;LinearLayout item=card();String date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(new Date(meta.optLong("startedAt",directory.lastModified())));item.addView(text(date,17,INK,true));button(item,"打开记录",true,()->localWork("",()->recording=Recording.read(directory),()->{viewingHistory=true;tab=5;render();}));button(item,"删除记录",false,()->new AlertDialog.Builder(this).setTitle("删除此记录？").setMessage("删除本机保存的此场录制，已导出的文件不受影响。").setPositiveButton("删除",(d,i)->localWork("记录已删除",()->{SessionArchive archive=new SessionArchive(getFilesDir());archive.remove(directory);histories=archive.list("*");},this::render)).setNegativeButton("取消",null).show());}catch(IOException e){c.addView(text("一条历史说明无法读取",12,MUTED,false));}}if(count==0)c.addView(text("此应用暂无历史记录",14,INK,false));
    }
    private void exportSession(){if(busy||recording.running()||recording.summaryMs<1000)return;exportId=recording.id;waitingDocument=true;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"lighttuner-"+selectedApp+"-"+exportId.substring(0,8)+".zip"),60);}catch(ActivityNotFoundException e){waitingDocument=false;toast("系统没有可用的文件保存界面");}}
    @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=60)return;waitingDocument=false;if(result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();String id=exportId;localWork("分析包已导出",()->{Recording session=Recording.read(new File(getFilesDir(),"sessions/"+id));try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("文件无法写入");SessionArchive.export(session,out);}},()->{});}

    private void saveThreads(){try{getPreferences(0).edit().putString("threads",threadRules.json()).apply();}catch(Exception e){toast(e.getMessage());}}
    private void threadDialog(String pkg,String version,String name,int mask){
        if(!PolicyBook.packageAllowed(pkg)){toast("系统界面、KernelSU 和轻调自身不支持线程限制");return;}try{if(!Long.toString(getPackageManager().getPackageInfo(pkg,0).getLongVersionCode()).equals(version)){toast("应用版本已变化，请重新录制");return;}}catch(PackageManager.NameNotFoundException e){toast("此应用已卸载");return;}
        LinearLayout panel=column();panel.setPadding(dp(24),dp(10),dp(24),dp(12));panel.addView(text(appName(pkg)+"\n应用版本："+version,13,MUTED,false));EditText pattern=new EditText(this);pattern.setSingleLine(true);pattern.setText(name);pattern.setTextColor(INK);pattern.setHint("线程名称，支持 * 通配符");panel.addView(pattern);panel.addView(text("规则匹配该版本的同名线程，仍受应用 cpuset 约束\n核心分配不关闭硬件核心，关键线程请谨慎\n试用未确认时自动恢复，确认后才保存规则",12,MUTED,false));CheckBox[] cores=new CheckBox[8];for(int start=0;start<8;start+=4){LinearLayout line=row();for(int cpu=start;cpu<start+4;cpu++){CheckBox b=new CheckBox(this);b.setText("CPU"+cpu);b.setTextSize(12);b.setTextColor(INK);b.setChecked((mask&(1<<cpu))!=0);cores[cpu]=b;line.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}panel.addView(line);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("线程核心分配").setView(panel).setPositiveButton(snapshot!=null&&snapshot.persistent?"试用 20 秒":"保存草稿",null).setNegativeButton("取消",null).create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int selected=0;for(int i=0;i<8;i++)if(cores[i].isChecked())selected|=1<<i;ThreadRules prepared=ThreadRules.parse(threadRules.json());prepared.put(new ThreadRules.Rule(pkg,version,pattern.getText().toString().trim(),selected,true));dialog.dismiss();proposeThreads(prepared);}catch(Exception e){toast(e.getMessage());}});
    }
    private void proposeThreads(ThreadRules prepared){
        if(busy)return;try{String json=prepared.json();if(snapshot==null||!snapshot.persistent){threadRules=prepared;saveThreads();render();toast("线程草稿已保存，启用接管后可试用");return;}run("试用线程策略",c->{PlatformController.Snapshot next=c.trialThreads(prepared);getPreferences(0).edit().putString("pendingThreads",json).putString("pendingThreadToken",next.threadToken).apply();return next;});}catch(Exception e){toast(e.getMessage());}
    }
    private void reconcileThreads(){
        String pending=getPreferences(0).getString("pendingThreadToken","");if(pending.isEmpty()||snapshot==null)return;if(!snapshot.threadToken.isEmpty())return;
        if(pending.equals(snapshot.threadConfirmed))try{threadRules=ThreadRules.parse(getPreferences(0).getString("pendingThreads",""));saveThreads();}catch(Exception e){record("已确认线程配置无法读取："+e.getMessage());}
        getPreferences(0).edit().remove("pendingThreads").remove("pendingThreadToken").apply();
    }
    private void threadTrialWindow(){
        if(!visible)return;String token=snapshot.threadToken;if(trialDialog==null||!dialogToken.equals(token)){if(trialDialog!=null)trialDialog.dismiss();dialogToken=token;LinearLayout panel=column();panel.setPadding(dp(24),dp(8),dp(24),dp(12));trialText=text("",32,GREEN,true);panel.addView(trialText);panel.addView(text("请观察当前应用是否流畅\n确认后保存线程规则，未确认自动恢复\n退出界面或界面进程被杀，回滚守护继续运行",14,MUTED,false));trialDialog=new AlertDialog.Builder(this).setTitle("线程策略试用中").setView(panel).setPositiveButton("确认应用",null).setNegativeButton("取消",null).create();trialDialog.setCancelable(false);trialDialog.show();flatButton(trialDialog.getButton(-1),false,12);flatButton(trialDialog.getButton(-2),false,12);trialDialog.getButton(-1).setOnClickListener(v->run("确认线程策略",c->c.confirmThreads(token)));trialDialog.getButton(-2).setOnClickListener(v->run("撤回线程试用",c->c.rollbackThreads(token)));}
        long seconds=Math.max(0,(threadDeadline-SystemClock.elapsedRealtime()+999)/1000);countdownText(seconds>0?seconds+" 秒":"正在撤回…");trialDialog.getButton(-1).setEnabled(!busy&&seconds>0&&token.equals(getPreferences(0).getString("pendingThreadToken","")));trialDialog.getButton(-2).setEnabled(!busy&&seconds>0);
    }
    private void renderThreads(){
        LinearLayout c=card();c.addView(text("只处理本应用指定版本、指定名称的线程\n每 3 秒识别新线程，空规则不扫描\n修改先试用，确认后保存，删除后恢复本项目写入值",12,MUTED,false));if(snapshot!=null&&!snapshot.persistent)c.addView(text("目前未启用接管，保存的规则为草稿",13,GREEN,true));button(c,"添加线程规则",true,()->{try{threadDialog(selectedApp,Long.toString(getPackageManager().getPackageInfo(selectedApp,0).getLongVersionCode()),"",255);}catch(Exception e){toast(e.getMessage());}});button(c,"试用已保存的线程规则",false,()->proposeThreads(threadRules)).setEnabled(!busy&&snapshot!=null&&snapshot.persistent);
        boolean found=false;for(ThreadRules.Rule rule:new ArrayList<>(threadRules.entries)){if(!rule.packageName.equals(selectedApp))continue;found=true;LinearLayout row=card();row.addView(text(rule.threadName,16,INK,true));row.addView(text("版本 "+rule.versionCode+"\nCPU "+cpuList(rule.cpuMask)+"\n"+(rule.enabled?"已启用":"已停用"),12,MUTED,false));button(row,"更改允许核心",false,()->threadDialog(rule.packageName,rule.versionCode,rule.threadName,rule.cpuMask));button(row,rule.enabled?"停用规则":"启用规则",false,()->{try{ThreadRules prepared=ThreadRules.parse(threadRules.json());for(ThreadRules.Rule copy:prepared.entries)if(copy.packageName.equals(rule.packageName)&&copy.versionCode.equals(rule.versionCode)&&copy.threadName.equals(rule.threadName))copy.enabled=!copy.enabled;proposeThreads(prepared);}catch(Exception e){toast(e.getMessage());}});button(row,"删除规则",false,()->{try{ThreadRules prepared=ThreadRules.parse(threadRules.json());prepared.entries.removeIf(r->r.packageName.equals(rule.packageName)&&r.versionCode.equals(rule.versionCode)&&r.threadName.equals(rule.threadName));proposeThreads(prepared);}catch(Exception e){toast(e.getMessage());}});}if(!found)c.addView(text("暂无线程策略，可以先录制并分析",14,INK,false));
    }

    private void renderCloud(){
        LinearLayout c=card();c.addView(text("线程汇集",18,INK,true));c.addView(text("手动上传录制中的应用包名、版本、线程名称、平均与最高占用及实例数量\n不上传 UID、PID、TID、进程命令行、屏幕内容或硬件设备标识\n使用随机客户端编号限制重复上传",13,MUTED,false));button(c,"上传本次录制统计",true,()->new AlertDialog.Builder(this).setTitle("上传录制统计").setMessage("发送到你的 Cloudflare 线程汇集服务，仅上传此页说明的应用和线程统计。").setPositiveButton("上传",(d,i)->{try{String client=getPreferences(0).getString("cloudClient","");if(client.isEmpty()){client=UUID.randomUUID().toString();getPreferences(0).edit().putString("cloudClient",client).apply();}org.json.JSONObject report=recording.upload(client);run("上传录制统计",root->{CloudHub.request("/v1/reports",report);return snapshot;});}catch(Exception e){toast(e.getMessage());}}).setNegativeButton("取消",null).show()).setEnabled(!busy&&recording.elapsedMs>=1000&&!recording.phase.equals("recording")&&!recording.phase.equals("starting"));
        button(c,"下载已发布线程策略",false,()->run("下载共享策略",root->{sharedRules=CloudHub.download();return snapshot;})).setEnabled(!busy);c.addView(text("下载只展示规则，确认后才合并到本地\n仅匹配指定应用版本；更新应用后旧规则不跨版本执行",12,MUTED,false));
        if(sharedRules.entries.isEmpty()){c.addView(text("尚无已下载策略",13,MUTED,false));return;}for(ThreadRules.Rule rule:sharedRules.entries){LinearLayout item=card();item.addView(text(rule.packageName+"\n"+rule.threadName,15,INK,true));item.addView(text("版本 "+rule.versionCode+"\n允许 CPU "+cpuList(rule.cpuMask)+(rule.enabled?"":"\n发布者已停用"),13,MUTED,false));}
        LinearLayout actions=card();button(actions,"试用下载策略",true,()->{try{ThreadRules prepared=ThreadRules.parse(threadRules.json());int accepted=0;for(ThreadRules.Rule rule:sharedRules.entries){try{if(Long.toString(getPackageManager().getPackageInfo(rule.packageName,0).getLongVersionCode()).equals(rule.versionCode)){prepared.put(rule);accepted++;}}catch(PackageManager.NameNotFoundException ignored){}}if(accepted==0){toast("没有匹配已安装应用版本的策略");return;}proposeThreads(prepared);}catch(Exception e){toast(e.getMessage());}}).setEnabled(!busy);
    }
    private void renderRecovery(){LinearLayout c=card();c.addView(text("后台与重启恢复",18,INK,true));c.addView(text("确认配置后自动开启，结束接管后关闭\n界面退出或被杀不影响 Root 调度\n守护每 5 秒检查监听和线程调度进程\n守护本身退出时，应用打开或系统周期任务恢复\n重启由 KernelSU 启动脚本和开机广播恢复已确认配置",13,MUTED,false));if(snapshot!=null)c.addView(text("重启恢复："+(snapshot.persistent?"已开启":"未开启")+"\n后台守护："+(snapshot.guardian?"运行中":"未运行"),14,INK,true));button(c,"恢复后台调度",true,()->run("恢复后台调度",PlatformController::recover)).setEnabled(!busy);LinearLayout window=card();window.addView(text("小窗与迷你窗",18,INK,true));window.addView(text("合并焦点事件，保留短时小窗场景\n同时可见应用取频率上限和核心并集\n小窗前台与顶层保留 CPU4–7\n使用均衡预设和流畅度下限，系统温控继续生效\n仅监听事件，不持续扫描窗口",13,MUTED,false));}
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
    private void countdownText(String value){if(TextUtils.equals(trialText.getText(),value))return;trialText.setText(value);if(ValueAnimator.areAnimatorsEnabled()){trialText.setAlpha(0.65f);trialText.animate().alpha(1).setDuration(120).start();}}
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
