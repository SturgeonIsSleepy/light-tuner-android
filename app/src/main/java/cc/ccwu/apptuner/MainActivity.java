package cc.ccwu.apptuner;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xfff4f6f0,INK=0xff20372e,GREEN=0xff176b53,MUTED=0xff6b7b72;
    private static final String[] GROUP_LABELS={"用户后台","系统后台","前台进程","顶层进程"};
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PlatformController controller;
    private PlatformController.Snapshot snapshot;
    private PolicyBook book;
    private LinearLayout content;
    private ScrollView scroller;
    private TextView stateText,detailText,infoText;
    private Spinner[] lower=new Spinner[4],upper=new Spinner[4];
    private CheckBox[][] boxes=new CheckBox[4][8];
    private Button applyButton,confirmButton,autoButton,restoreButton,syncButton,readButton;
    private int tab=0,editing=1,rechecks=0,ticks=0;
    private String editingApp=null,message="正在读取完整设备档位",pendingExport="";
    private boolean busy,waitingDocument,visible;
    private long trialDeadline;
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            if(!visible) return;
            updateStatus();
            if(snapshot!=null && !busy) {
                boolean pending=snapshot.phase.equals("trial") || snapshot.phase.equals("restoring");
                boolean settling=(snapshot.phase.equals("active") || snapshot.phase.equals("legacy")) && !snapshot.matches && rechecks<3;
                if((pending && ++ticks%2==0) || (settling && ++ticks%2==0)) { if(settling) rechecks++; MainActivity.this.run("自动复核读数",PlatformController::probe); }
            }
            handler.postDelayed(this,1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null) { waitingDocument=saved.getBoolean("document"); pendingExport=saved.getString("export",""); }
        LinearLayout root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});
        scroller=new ScrollView(this); content=column(); content.setPadding(dp(18),dp(14),dp(18),dp(24));scroller.addView(content);root.addView(scroller,new LinearLayout.LayoutParams(-1,-1));setContentView(root);render();
        root.post(()->{WindowInsetsController b=getWindow().getInsetsController();if(b!=null)b.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);});
    }
    @Override public void onResume(){super.onResume();visible=true;rechecks=0;ticks=0;handler.removeCallbacks(ticker);handler.post(ticker);if(!busy)run("读取配置与目标值",PlatformController::probe);}
    @Override public void onPause(){visible=false;handler.removeCallbacks(ticker);super.onPause();}
    @Override public void onStop(){super.onStop();if(!waitingDocument&&!isChangingConfigurations())finish();}
    @Override public void onDestroy(){handler.removeCallbacks(ticker);io.execute(()->{if(controller!=null)controller.close();});io.shutdown();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle b){b.putBoolean("document",waitingDocument);b.putString("export",pendingExport);super.onSaveInstanceState(b);}
    private interface Operation{PlatformController.Snapshot execute(PlatformController c)throws Exception;}
    private void run(String label,Operation op){
        if(busy)return;busy=true;message=label;updateStatus();
        io.execute(()->{
            PlatformController.Snapshot next=null;PolicyBook initial=null;String result;long start=SystemClock.elapsedRealtime();
            try{
                if(controller==null)controller=new PlatformController(this);next=op.execute(controller);
                if(book==null){
                    String saved=getPreferences(0).getString("book3","");
                    if(saved.isEmpty())initial=next.defaults();else initial=PolicyBook.parse(saved);
                    for(PlatformProfile p:initial.presets)next.validate(p);
                    for(PolicyBook.Rule r:initial.apps.values())if(r.custom!=null)next.validate(r.custom);
                }
                result=label+"完成 · "+(SystemClock.elapsedRealtime()-start)+" ms";
            }catch(Exception e){result=e.getMessage()==null?e.toString():e.getMessage();if(controller!=null){controller.close();controller=null;}}
            PlatformController.Snapshot read=next;PolicyBook loaded=initial;String info=result;
            runOnUiThread(()->{if(isDestroyed())return;busy=false;message=info;if(read!=null){snapshot=read;trialDeadline=SystemClock.elapsedRealtime()+read.remaining*1000L;}if(loaded!=null){book=loaded;editing=PolicyBook.index(book.global);saveLocal();render();}else if(stateText==null)render();updateStatus();});
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
    private void saveEditor(){try{storeForm();message="设置已保存为草稿，尚未同步硬件";updateStatus();}catch(Exception e){toast(e.getMessage());}}
    private void tryEditor(){try{storeForm();PlatformProfile p=editor();if(editingApp==null){book.global=PolicyBook.IDS[editing];saveLocal();}run("试用编辑配置",c->c.apply(p));scroller.smoothScrollTo(0,0);}catch(Exception e){toast(e.getMessage());}}
    private void confirm(){String token=snapshot.token;run("保留本次配置",c->{PlatformController.Snapshot s=c.confirm(token);c.installBook(book);return s;});}
    private void sync(){saveLocal();run("同步三预设与分应用策略",c->c.reload(book));}
    private void updateStatus(){
        if(stateText==null)return;
        if(snapshot==null)stateText.setText(busy?"读取设备中":"需要 Root 权限");
        else if(snapshot.phase.equals("trial"))stateText.setText("本次试用 · "+Math.max(0,(trialDeadline-SystemClock.elapsedRealtime()+999)/1000)+" 秒后撤回");
        else stateText.setText(snapshot.status(rechecks));
        if(infoText!=null)infoText.setText(message);
        if(detailText!=null&&snapshot!=null){
            StringBuilder out=new StringBuilder("最近回读\n");
            for(int i=0;i<3;i++)out.append(cluster(i)).append("  ").append(mhz(snapshot.min[i])).append(" – ").append(mhz(snapshot.max[i])).append('\n');
            out.append("GPU  ").append(mhz(snapshot.gpuMin)).append(" – ").append(mhz(snapshot.gpuMax)).append('\n');
            if(snapshot.phase.equals("active")||snapshot.phase.equals("legacy")||snapshot.phase.equals("trial"))try{PlatformProfile target=snapshot.intent();out.append("\n保存的目标\n");for(int i=0;i<3;i++)out.append(cluster(i)).append("  ").append(mhz(target.min[i])).append(" – ").append(mhz(target.max[i])).append('\n');out.append("GPU  ").append(mhz(target.gpuMin)).append(" – ").append(mhz(target.gpuMax)).append('\n');}catch(Exception e){out.append("目标数据无法读取\n");}
            out.append("\n自动切换：").append(snapshot.auto?(snapshot.watch?"运行中":"监听异常"):"已关闭");if(!snapshot.foreground.isEmpty())out.append("\n前台应用：").append(snapshot.foreground);if(!snapshot.watchError.isEmpty())out.append("\n切换提示：").append(snapshot.watchError);
            detailText.setText(out.toString());
        }
        if(readButton!=null)readButton.setEnabled(!busy);
        if(applyButton!=null)applyButton.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("restoring")&&!snapshot.phase.equals("restore-failed"));
        if(confirmButton!=null){confirmButton.setVisibility(snapshot!=null&&snapshot.phase.equals("trial")?View.VISIBLE:View.GONE);confirmButton.setEnabled(!busy&&snapshot!=null&&snapshot.phase.equals("trial")&&trialDeadline>SystemClock.elapsedRealtime());}
        if(autoButton!=null){autoButton.setEnabled(!busy&&snapshot!=null&&book!=null&&!snapshot.phase.equals("trial"));autoButton.setText(snapshot!=null&&snapshot.auto?"关闭分应用切换，回到全局":"启用分应用自动切换");}
        if(restoreButton!=null)restoreButton.setEnabled(!busy&&snapshot!=null);
        if(syncButton!=null)syncButton.setEnabled(!busy&&book!=null&&snapshot!=null&&!snapshot.phase.equals("trial"));
    }
    private void render(){
        content.removeAllViews();stateText=detailText=infoText=null;applyButton=confirmButton=autoButton=restoreButton=syncButton=readButton=null;
        content.addView(text("轻调",28,INK,true));content.addView(text("天玑 9400+ · CPU / GPU · 0.3.0",13,GREEN,true));space(content,14);
        LinearLayout status=card();stateText=text("",18,INK,true);status.addView(stateText);infoText=text(message,12,MUTED,false);space(status,6);status.addView(infoText);readButton=button(status,"刷新读数",false,()->run("刷新读数",PlatformController::probe));confirmButton=button(status,"确认本次试用",true,this::confirm);
        LinearLayout nav=row();for(int i=0;i<3;i++){final int index=i;Button b=new Button(this);b.setText(new String[]{"预设配置","分应用","状态与文件"}[i]);b.setAllCaps(false);b.setTextSize(12);nav.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));b.setOnClickListener(v->{tab=index;render();});}content.addView(nav);space(content,10);
        if(snapshot==null||book==null){LinearLayout c=card();c.addView(text("在 KernelSU 授予轻调 Root 权限后重新启动\n正在读取 CPU、GPU 和四类进程组",14,INK,false));restoreButton=button(c,"尝试恢复已有记录",false,()->run("恢复接管前设置",PlatformController::restore));updateStatus();return;}
        if(tab==0)renderEditor();else if(tab==1)renderApps();else renderFiles();updateStatus();
    }
    private void renderEditor(){
        LinearLayout global=card();global.addView(text("全局默认配置",18,INK,true));Spinner choose=selector(Arrays.asList(PolicyBook.NAMES),PolicyBook.index(book.global));global.addView(choose);
        choose.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int pos,long id){book.global=PolicyBook.IDS[pos];saveLocal();}public void onNothingSelected(AdapterView<?>p){}});
        button(global,"试用全局默认",true,()->run("试用全局默认",c->c.apply(book.profile(book.global))));
        LinearLayout title=card();
        if(editingApp==null){title.addView(text("编辑三套预设",18,INK,true));Spinner pick=selector(Arrays.asList(PolicyBook.NAMES),editing);title.addView(pick);pick.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int pos,long id){if(pos!=editing){editing=pos;render();}}public void onNothingSelected(AdapterView<?>p){}});}
        else{title.addView(text("应用独立设置",18,INK,true));title.addView(text(editingApp,12,MUTED,false));button(title,"返回三套预设",false,()->{editingApp=null;render();});}
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
        button(c,"添加已安装应用",true,this::chooseApp);autoButton=button(c,"",true,()->{PolicyBook captured=book;run("更新自动切换",x->snapshot.auto?x.stopAuto(captured):x.startAuto(captured));});syncButton=button(c,"同步已保存的策略",false,this::sync);
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
            else if(which==3){editingApp=pkg;tab=0;render();}
            else if(which==4){r.enabled=!r.enabled;saveLocal();render();}
            else{book.apps.remove(pkg);saveLocal();render();}
        }).show();
    }
    private String appName(String pkg){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){return pkg;}}
    private void chooseApp(){
        List<ResolveInfo> installed=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);TreeMap<String,String> names=new TreeMap<>();
        for(ResolveInfo r:installed){String pkg=r.activityInfo.packageName;if(PolicyBook.packageAllowed(pkg))names.put(appName(pkg)+"\n"+pkg,pkg);}
        String[] labels=names.keySet().toArray(new String[0]),packages=names.values().toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("选择应用").setItems(labels,(d,i)->{book.apps.putIfAbsent(packages[i],new PolicyBook.Rule());saveLocal();render();ruleDialog(packages[i]);}).setNegativeButton("取消",null).show();
    }
    private void renderFiles(){
        LinearLayout live=card();live.addView(text("目标与读数",18,INK,true));detailText=text("",12,MUTED,false);live.addView(detailText);space(live,10);live.addView(text("打开时自动读取，读数不同会再自动复核\n启动加速、温控或其他工具可能临时改变读数\n不凭一次读数判断外部修改，不反复抢写",12,MUTED,false));
        LinearLayout files=card();files.addView(text("配置集文件",18,INK,true));button(files,"导入配置集 JSON",false,()->{waitingDocument=true;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),1);});
        button(files,"导出三预设与分应用策略",false,()->{try{pendingExport=book.json().toString(2);waitingDocument=true;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"mt6991-policies-v3.json"),2);}catch(Exception e){toast(e.getMessage());}});
        button(files,"查看导入规范",false,()->new AlertDialog.Builder(this).setTitle("配置集 v3").setMessage("UTF-8 JSON，最大 64 KiB\nschemaVersion：3，platform：mt6991\nglobalPreset：全局预设名称\npresets：powersave / balanced / performance\n每套包含三簇 CPU、GPU 范围和四组核心\nCPU 与 GPU 频率均用整数 kHz\napplications：包名、enabled、preset、可选 custom\n\n导入只更新草稿。旧 v2 全局文件可导入均衡预设，补齐 GPU 和新增进程组。").setPositiveButton("知道了",null).show());
        syncButton=button(files,"同步已保存的策略",true,this::sync);autoButton=button(files,"",true,()->run("更新自动切换",c->snapshot.auto?c.stopAuto(book):c.startAuto(book)));restoreButton=button(files,"结束接管并恢复",false,()->run("恢复接管前设置",PlatformController::restore));
        space(content,8);content.addView(text("自动切换监听前台事件，无持续进程扫描\n其他工具不要同时改写相同的 CPU/GPU 节点\n不停止系统性能、温控或充电保护服务",12,MUTED,false));
    }
    @Override public void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);waitingDocument=false;if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();busy=true;message=req==1?"读取配置集":"导出配置集";updateStatus();PlatformController.Snapshot device=snapshot;PolicyBook existing=book;
        io.execute(()->{PolicyBook imported=null;String outcome;
            try{
                if(req==1){String source;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("文件无法读取");byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>65536)throw new IOException("文件超过 64 KiB");}source=new String(out.toByteArray(),StandardCharsets.UTF_8);}
                    boolean legacy=PlatformProfile.integer(PlatformProfile.object(source),"schemaVersion")==2;PolicyBook parsed;
                    if(legacy){parsed=PolicyBook.parse(existing.json().toString());parsed.presets[1]=PlatformProfile.legacy(PlatformProfile.object(source),device.current());}else parsed=PolicyBook.parse(source);
                    for(PlatformProfile p:parsed.presets)device.validate(p);for(PolicyBook.Rule r:parsed.apps.values())if(r.custom!=null)device.validate(r.custom);
                    imported=parsed;outcome=legacy?"v2 已导入均衡预设，新增项取设备值，尚未同步":"配置集已导入草稿，尚未同步";
                }else{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("文件无法写入");out.write(pendingExport.getBytes(StandardCharsets.UTF_8));}outcome="配置集已导出";}
            }catch(Exception e){outcome="文件操作失败："+e.getMessage();}
            PolicyBook checked=imported;String info=outcome;runOnUiThread(()->{if(isDestroyed())return;busy=false;message=info;if(checked!=null){book=checked;editingApp=null;saveLocal();render();}updateStatus();});
        });
    }
    private Spinner selector(List<String> labels,int chosen){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setSelection(chosen);return s;}
    private Spinner frequencies(List<Integer> values,int chosen){List<String>labels=new ArrayList<>();for(int n:values)labels.add(mhz(n));return selector(labels,Math.max(0,values.indexOf(chosen)));}
    private static String mhz(int k){return k/1000+" MHz";}
    private static String cluster(int i){return i==0?"CPU0–3":i==1?"CPU4–6":"CPU7";}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(2),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void space(LinearLayout c,int height){c.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(15),dp(15),dp(15),dp(15));c.setBackground(background(0xffffffff,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);content.addView(c,p);return c;}
    private Button button(LinearLayout c,String label,boolean primary,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?0xffffffff:GREEN);b.setBackground(background(primary?GREEN:0xffe6eee0,12));space(c,10);c.addView(b,new LinearLayout.LayoutParams(-1,dp(46)));b.setOnClickListener(v->action.run());return b;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
