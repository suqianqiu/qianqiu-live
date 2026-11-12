package com.tv.simplelive;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Remote-friendly update page. Download is explicit; installation uses system confirmation. */
public final class UpdateActivity extends Activity {
    private AppUpdate update;
    private TextView status,detail,checked,permission;
    private ProgressBar progress;
    private Button primary,refresh;
    private boolean resumed,awaitPermission;
    private final AppUpdate.Listener observer=this::render;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xFFE7E6F3);return t;}
    private Button button(String value,Runnable action){
        Button b=new Button(this);b.setId(View.generateViewId());b.setText(value);b.setTextSize(18);b.setAllCaps(false);
        StateListDrawable states=new StateListDrawable();
        for(int[] state:new int[][]{new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_pressed},new int[]{}}){GradientDrawable d=new GradientDrawable();d.setColor(state.length>0?0xFF7760A9:0xFF30334A);d.setCornerRadius(dp(7));d.setStroke(dp(2),state.length>0?0xFFF0DB90:0xFF565971);states.addState(state,d);}
        b.setBackground(states);b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xFF888898,0xFFFFFFFF}));b.setOnClickListener(v->action.run());return b;
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(1024,1024);awaitPermission=state!=null&&state.getBoolean("awaitPermission");update=AppUpdate.get(this);build();}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(36),dp(18),dp(36),dp(18));root.setBackgroundColor(0xFF151727);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);Button back=button("返回",this::finish);header.addView(back,new LinearLayout.LayoutParams(dp(100),dp(44)));TextView title=text(getApplicationInfo().loadLabel(getPackageManager())+" · 版本更新",27);title.setPadding(dp(20),0,0,0);header.addView(title);root.addView(header);
        TextView current=text("当前版本 "+update.currentVersion()+"  ·  每天首次打开自动检查",17);current.setPadding(0,dp(12),0,dp(10));root.addView(current);
        status=text("检查新版本",24);root.addView(status);checked=text("",14);checked.setPadding(0,dp(5),0,dp(8));root.addView(checked);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(10)));
        ScrollView scroll=new ScrollView(this);detail=text("",18);detail.setPadding(0,dp(12),0,dp(12));scroll.addView(detail);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        permission=text("",14);root.addView(permission);TextView note=text("覆盖安装保留设置、历史和下载内容。安装时应用会暂时退出，请在空闲时升级。",14);note.setPadding(0,dp(8),0,dp(12));root.addView(note);
        LinearLayout actions=new LinearLayout(this);primary=button("检查更新",this::act);refresh=button("重新检查",()->update.check(true));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),2);lp.rightMargin=dp(12);actions.addView(primary,lp);actions.addView(refresh,new LinearLayout.LayoutParams(0,dp(46),1));root.addView(actions);primary.setNextFocusRightId(refresh.getId());refresh.setNextFocusLeftId(primary.getId());primary.setFocusableInTouchMode(true);primary.requestFocus();render(update.state());
    }
    private void render(AppUpdate.State s){
        if(isFinishing()||status==null)return;String label;
        switch(s.phase){case CHECKING:label="正在检查更新…";break;case CURRENT:label="当前已是最新版本";break;case AVAILABLE:label="发现新版本 "+s.release.version;break;case DOWNLOADING:label="正在下载更新 · "+(100*s.bytes/s.release.size)+"%";break;case PAUSING:label="正在暂停下载…";break;case PAUSED:label="下载已暂停，可继续";break;case VERIFYING:label="正在校验安装包…";break;case READY:label="更新已准备好";break;case INSTALLING:label="正在安装更新…";break;case ERROR:label="暂时无法检查更新";break;default:label="检查新版本";}
        status.setText(label);checked.setText(s.checked>0?"上次成功检查 "+new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA).format(new Date(s.checked)):"尚未成功检查");
        progress.setVisibility(s.phase==AppUpdate.Phase.DOWNLOADING||s.phase==AppUpdate.Phase.PAUSING||s.phase==AppUpdate.Phase.VERIFYING?View.VISIBLE:View.GONE);progress.setIndeterminate(s.phase==AppUpdate.Phase.VERIFYING);if(s.release!=null)progress.setProgress((int)Math.min(1000,s.bytes*1000/s.release.size));
        String copy=s.message;if(s.newer)copy="新版本 "+s.release.version+"  ·  "+String.format(Locale.CHINA,"%.1f MB",s.release.size/1000000.0)+"\n\n"+s.release.notes+"\n\n"+s.message;else if(s.phase==AppUpdate.Phase.CURRENT)copy="每天首次打开会在后台检查，也可以在这里手动检查。\n网络不可用不影响正常观看。";detail.setText(copy);
        permission.setText(update.canInstall()?"安装授权已开启":"首次安装更新时将引导开启安装授权。");
        primary.setText(s.phase==AppUpdate.Phase.DOWNLOADING?"暂停下载":s.busy()?"请稍候…":s.ready?"立即安装":s.newer?s.bytes>0?"继续下载":"下载更新":"检查更新");primary.setEnabled(!s.busy()||s.phase==AppUpdate.Phase.DOWNLOADING);refresh.setEnabled(!s.busy());if(s.newer)update.markPrompted();
    }
    private void act(){AppUpdate.State s=update.state();if(s.phase==AppUpdate.Phase.DOWNLOADING){update.pause();return;}if(s.busy())return;if(s.ready){if(update.canInstall())update.install();else new AlertDialog.Builder(this).setTitle("允许安装更新").setMessage("在电视设置中允许此应用安装更新。开启后返回，将继续本次安装。").setPositiveButton("前往设置",(d,w)->openPermission()).setNegativeButton("稍后",null).show();}else if(s.newer)update.download();else update.check(true);}
    private void openPermission(){
        Intent scoped=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));Intent[] routes=Build.VERSION.SDK_INT>=26?new Intent[]{scoped,new Intent(Settings.ACTION_SECURITY_SETTINGS),new Intent(Settings.ACTION_SETTINGS)}:new Intent[]{new Intent(Settings.ACTION_SECURITY_SETTINGS),new Intent(Settings.ACTION_SETTINGS)};
        for(Intent route:routes)try{awaitPermission=true;startActivityForResult(route,91);return;}catch(ActivityNotFoundException|SecurityException ignored){awaitPermission=false;}
        new AlertDialog.Builder(this).setTitle("请在电视设置中允许安装").setMessage("此电视没有提供直接授权入口。请在安全或应用安装设置中允许第三方安装，再返回重试。").setPositiveButton("知道了",null).show();
    }
    boolean active(){return resumed&&!isFinishing();}
    void confirmInstall(Intent approval){try{startActivityForResult(approval,92);}catch(Exception e){update.cancelInstall("无法打开系统安装页面，请检查电视安装限制");}}
    @Override protected void onStart(){super.onStart();update.add(observer);}
    @Override protected void onResume(){super.onResume();resumed=true;update.attach(this);if(awaitPermission){awaitPermission=false;if(update.canInstall())update.install();else permission.setText("尚未开启安装授权，可重试。");}render(update.state());}
    @Override protected void onPause(){resumed=false;update.detach(this);super.onPause();}
    @Override protected void onStop(){update.remove(observer);update.pause();super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("awaitPermission",awaitPermission);}
}
