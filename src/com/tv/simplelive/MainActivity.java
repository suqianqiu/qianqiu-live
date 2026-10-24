package com.tv.simplelive;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final String PUBLIC_LIST = "https://iptv-org.github.io/iptv/countries/cn.m3u";
    private static final String DEMO = "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4";
    private static final int FILE_PICKER = 101;
    private static final int BUNDLE_REVISION = 6;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService playerWorker = Executors.newSingleThreadExecutor();
    private int closingPlayers;
    private boolean pendingPlay, destroyed;
    private SourceInspector inspector;
    private volatile int inspectionGeneration;
    private volatile boolean inspectionForeground;
    private boolean inspectionManual;
    private String inspectionSummary = "尚未巡检";
    private final HashMap<String,SourceInspector.Health> channelHealth = new HashMap<String,SourceInspector.Health>();
    private final ArrayList<Playlist.Channel> channels = new ArrayList<Playlist.Channel>();
    private LibVLC vlc;
    private MediaPlayer player;
    private SurfaceView surface;
    private View videoCover;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView status, heading, hint, playbackError, inspectionStatus;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final ArrayList<String> names = new ArrayList<String>();
    private android.content.SharedPreferences prefs;
    private int mode, index = -1, playGeneration, loadGeneration;
    private boolean foreground, softwareRetry, seenVideo;
    private String source = "", currentDecode = "", state = "等待选台";
    private static final String[] MODES = {"自动：优先硬解，失败后尝试软解", "仅硬解", "仅软解"};
    private final Runnable hideHint = new Runnable() {
        public void run() { if (panel.getVisibility() != View.VISIBLE) hint.setVisibility(View.GONE); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        mode = Math.max(0, Math.min(2, prefs.getInt("mode", 0)));
        source = prefs.getString("source", "");
        buildUi();
        loadInspectionHealth();
        try {
            ArrayList<String> options = new ArrayList<String>();
            options.add("--network-caching=1800");
            options.add("--no-video-title-show");
            options.add("--no-stats");
            options.add("--aout=opensles_android");
            vlc = new LibVLC(this, options);
        } catch (Throwable error) {
            setState("解码库初始化失败：" + error.getClass().getSimpleName());
            return;
        }
        // Updating our public list must not overwrite a user's custom network/local list.
        boolean refreshBundled = PUBLIC_LIST.equals(source)
            && prefs.getInt("bundleRevision", 0) < BUNDLE_REVISION;
        if (!refreshBundled) loadCache();
        if (channels.isEmpty()) {
            try (InputStream input = getAssets().open("public-cn.m3u")) {
                byte[] bundled = Playlist.read(input);
                channels.addAll(Playlist.parse(bundled, PUBLIC_LIST));
                source = PUBLIC_LIST;
                prefs.edit().putString("source", source).putInt("bundleRevision", BUNDLE_REVISION).remove("lastIndex").apply();
                try (FileOutputStream output = openFileOutput("playlist.m3u", MODE_PRIVATE)) { output.write(bundled); }
                catch (IOException cacheError) { android.util.Log.w("SimpleLiveTV", "Could not cache bundled list", cacheError); }
                updateList(); setState("已载入电脑筛选后的公开列表，按确定选台；源站可能变化");
            } catch (Exception error) {
                channels.add(new Playlist.Channel("演示片 · Big Buck Bunny（非直播）", DEMO, "测试"));
                updateList(); setState("选择演示片测试，或在设置中导入公开直播列表");
            }
        } else setState("已读取上次列表，按确定键选台");
    }
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private void buildUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        videoCover = new View(this); videoCover.setBackgroundColor(Color.BLACK);
        root.addView(videoCover, new FrameLayout.LayoutParams(-1, -1));
        playbackError = label("", 24, Color.WHITE);
        playbackError.setGravity(Gravity.CENTER);
        playbackError.setPadding(dp(40), dp(32), dp(40), dp(32));
        playbackError.setFocusable(false); playbackError.setClickable(false);
        playbackError.setVisibility(View.GONE);
        root.addView(playbackError, new FrameLayout.LayoutParams(-1, -1));
        surface.setFocusable(false);
        surface.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (player != null) player.getVLCVout().setWindowSize(r - l, b - t);
            }
        });
        hint = label("千秋电视", 20, Color.WHITE); hint.setPadding(dp(20), dp(12), dp(20), dp(12));
        hint.setMaxWidth(dp(600)); hint.setMaxLines(3); hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hint.setBackgroundColor(0xDD10202A);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        hp.setMargins(dp(16), dp(18), dp(18), 0); root.addView(hint, hp);
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(12)); panel.setBackgroundColor(0xF513222D);
        int width = Math.min(dp(430), getResources().getDisplayMetrics().widthPixels * 2 / 3);
        root.addView(panel, new FrameLayout.LayoutParams(width, -1, Gravity.LEFT));
        heading = label("千秋电视  /  LIVE", 27, 0xFF81E2CB); heading.setTypeface(null, Typeface.BOLD);
        panel.addView(heading);
        TextView guide = label("确定选台 · 返回全屏 · 菜单设置\n全屏时上下换台，左右切换解码", 14, 0xFFAFBFCD);
        guide.setPadding(0, dp(8), 0, dp(12)); panel.addView(guide);
        LinearLayout buttons = new LinearLayout(this);
        addButton(buttons, "设置", new Runnable() { public void run() { showSettings(); } });
        addButton(buttons, "全屏", new Runnable() { public void run() { showPanel(false); } });
        addButton(buttons, "巡检源", new Runnable() { public void run() { startInspection(true); } });
        panel.addView(buttons, new LinearLayout.LayoutParams(-1, dp(48)));
        list = new ListView(this); list.setDividerHeight(dp(1)); list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1, names) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                TextView row = (TextView)super.getView(position, convert, parent);
                row.setTextColor(Color.WHITE); row.setTextSize(18); row.setMinHeight(dp(52));
                row.setPadding(dp(10), dp(8), dp(10), dp(8)); return row;
            }
        };
        list.setAdapter(adapter); list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                play(position, false); showPanel(false);
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("正在准备播放器…", 14, 0xFFB9CBD9); status.setMaxLines(4);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setPadding(0, dp(8), 0, 0); panel.addView(status);
        inspectionStatus = label("尚未巡检", 13, 0xFF81E2CB); inspectionStatus.setMaxLines(3);
        inspectionStatus.setPadding(0, dp(6), 0, 0); panel.addView(inspectionStatus);
        list.requestFocus();
    }
    private void addButton(LinearLayout row, String text, final Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(16);
        button.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } });
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }
    private void updateList() {
        names.clear();
        for (int i = 0; i < channels.size(); i++) {
            Playlist.Channel c=channels.get(i); SourceInspector.Health h=channelHealth.get(c.url);
            String badge=h==null?"":"  · "+(h.testedAt>0&&!SourceInspector.fresh(h.testedAt,System.currentTimeMillis())?"到期待复测":h.state);
            names.add(String.format(java.util.Locale.US, "%02d  %s%s", i+1, c.name, badge));
        }
        adapter.notifyDataSetChanged();
        heading.setText("千秋电视  /  " + channels.size() + " 台");
    }
    private void showPanel(boolean visible) {
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams)panel.getLayoutParams();
        layout.width = player == null ? -1 : Math.min(dp(430), getResources().getDisplayMetrics().widthPixels * 2 / 3);
        panel.setLayoutParams(layout);
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) { list.requestFocus(); if (index >= 0) list.setSelection(index); }
        else { root.setFocusableInTouchMode(true); root.requestFocus(); }
        showHint();
    }
    private void setState(String text) { state = text; status.setText(text + "\n" + MODES[mode]); showHint(); }
    private void showHint() {
        if (playbackError.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideHint); hint.setVisibility(View.GONE); return;
        }
        hint.setText((index >= 0 && index < channels.size() ? channels.get(index).name + "\n" : "")
            + state + (currentDecode.isEmpty() ? "" : " · " + currentDecode));
        hint.setVisibility(View.VISIBLE); handler.removeCallbacks(hideHint); handler.postDelayed(hideHint, 6000);
    }
    private void disposePlayer() {
        playGeneration++;
        videoCover.setVisibility(View.VISIBLE);
        final MediaPlayer old = player;
        player = null; // Invalidate first: stale callbacks can never touch a closing player.
        if (old == null) return;
        closingPlayers++;
        PlayerCleanup.run("listener", new Runnable() { public void run() { old.setEventListener(null); } });
        // nativeStop may wait for network input threads. Never wait for it on the UI thread.
        playerWorker.execute(new Runnable() { public void run() {
            PlayerCleanup.run("stop", new Runnable() { public void run() { old.stop(); } });
            handler.post(new Runnable() { public void run() {
                // AWindow requires view detach on the main thread, after stop has completed.
                PlayerCleanup.run("detach", new Runnable() { public void run() { old.getVLCVout().detachViews(); } });
                playerWorker.execute(new Runnable() { public void run() {
                    PlayerCleanup.run("release", new Runnable() { public void run() { old.release(); } });
                    handler.post(new Runnable() { public void run() {
                        closingPlayers--;
                        if (destroyed) releaseVlcWhenIdle(); else startPendingPlayback();
                    } });
                } });
            } });
        } });
    }
    private void releaseVlcWhenIdle() {
        if (!destroyed || closingPlayers != 0) return;
        final LibVLC old = vlc; vlc = null;
        playerWorker.execute(new Runnable() { public void run() {
            if (old != null) PlayerCleanup.run("core-release", new Runnable() { public void run() { old.release(); } });
        } });
        playerWorker.shutdown();
    }
    private void play(int position, boolean retrySoft) {
        if (!foreground || vlc == null || channels.isEmpty()) return;
        if(inspector!=null&&!inspectionManual){cancelInspection();inspectionStatus.setText("播放时暂停自动深测；下次进入续测，或手动巡检");}
        disposePlayer();
        playbackError.setVisibility(View.GONE);
        index = (position + channels.size()) % channels.size();
        prefs.edit().putInt("lastIndex", index).apply();
        softwareRetry = retrySoft; seenVideo = false;
        currentDecode = (mode == 2 || retrySoft) ? "软解" : (mode == 1 ? "硬解" : "优先硬解");
        android.util.Log.i("SimpleLiveTV", "play mode=" + mode + " softwareRetry=" + retrySoft + " generation=" + playGeneration);
        setState(retrySoft ? "正在尝试软解" : "正在连接");
        list.setItemChecked(index, true);
        pendingPlay = true;
        if (closingPlayers > 0) {
            setState("正在切换频道；仍可按 ↑/↓ 继续选台");
            final int waitingGeneration = playGeneration;
            handler.postDelayed(new Runnable() { public void run() {
                if (waitingGeneration == playGeneration && foreground && pendingPlay && closingPlayers > 0)
                    showUnavailable("上一频道停止较慢，正在等待；仍可继续选台");
            } }, 5000);
        }
        startPendingPlayback();
    }
    private void startPendingPlayback() {
        if (!pendingPlay || closingPlayers != 0 || !foreground || destroyed || vlc == null) return;
        pendingPlay = false;
        playbackError.setVisibility(View.GONE);
        final int generation = playGeneration;
        try {
            player = new MediaPlayer(vlc);
            IVLCVout out = player.getVLCVout(); out.setVideoView(surface);
            out.setWindowSize(surface.getWidth(), surface.getHeight());
            out.attachViews(new IVLCVout.OnNewVideoLayoutListener() {
                public void onNewVideoLayout(IVLCVout vout, int w, int h, int vw, int vh, int sn, int sd) {
                    if (generation != playGeneration || player == null || w == 0 || h == 0) return;
                    seenVideo = true;
                    videoCover.setVisibility(View.GONE);
                    android.util.Log.i("SimpleLiveTV", "video-layout " + w + "x" + h + " mode=" + mode + " softRetry=" + softwareRetry);
                    // Preserve display aspect ratio, including non-square sample pixels.
                    double ratio = (double)(vw > 0 ? vw : w) / (vh > 0 ? vh : h);
                    if (sn > 0 && sd > 0) ratio *= (double)sn / sd;
                    int rw = root.getWidth(), rh = root.getHeight();
                    int nw = rw, nh = (int)(rw / ratio);
                    if (nh > rh) { nh = rh; nw = (int)(rh * ratio); }
                    surface.setLayoutParams(new FrameLayout.LayoutParams(nw, nh, Gravity.CENTER));
                }
            });
            player.setEventListener(new MediaPlayer.EventListener() {
                public void onEvent(MediaPlayer.Event event) {
                    if (generation != playGeneration || player == null || !foreground) return;
                    switch (event.type) {
                        case MediaPlayer.Event.Opening: setState("正在打开节目"); break;
                        case MediaPlayer.Event.Buffering:
                            if (event.getBuffering() < 100) setState("正在缓冲 " + (int)event.getBuffering() + "%"); break;
                        case MediaPlayer.Event.Playing: setState("正在播放"); break;
                        case MediaPlayer.Event.Vout:
                            if (event.getVoutCount() > 0) {
                                seenVideo = true; videoCover.setVisibility(View.GONE);
                                android.util.Log.i("SimpleLiveTV", "video-output ready mode=" + mode + " softRetry=" + softwareRetry);
                            }
                            break;
                        case MediaPlayer.Event.EncounteredError: failed(generation); break;
                        case MediaPlayer.Event.EndReached:
                            if (!seenVideo) failed(generation);
                            else setState("播放结束；按 ↑/↓ 换台，按返回回到列表"); break;
                    }
                }
            });
            Media media = new Media(vlc, Uri.parse(channels.get(index).url));
            try {
                media.setHWDecoderEnabled(mode != 2 && !softwareRetry, mode == 1);
                media.addOption(":network-caching=1800");
                player.setMedia(media);
            } finally { media.release(); }
            player.play();
            handler.postDelayed(new Runnable() {
                public void run() {
                    noVideo(generation);
                }
            }, 20000);
        } catch (Throwable error) {
            unavailable("播放组件错误：" + error.getClass().getSimpleName());
        }
    }
    private void noVideo(int generation) {
        if (generation != playGeneration || player == null || !foreground || seenVideo) return;
        if (mode == 0 && !softwareRetry) play(index, true);
        else unavailable("20 秒未出现画面，请检查直播源或网络");
    }
    private void unavailable(String reason) {
        pendingPlay = false;
        showUnavailable(reason);
        disposePlayer();
    }
    private void showUnavailable(String reason) {
        playbackError.setText((index >= 0 && index < channels.size() ? channels.get(index).name + "\n\n" : "")
            + "该电视台无法播放\n" + reason + "\n\n请继续按 ↑ 或 ↓ 切换电视台\n按返回（Back）回到电视台列表");
        playbackError.setVisibility(View.VISIBLE);
        setState("该电视台无法播放；按 ↑/↓ 换台，按返回回到列表");
        // Keep the current panel visibility and input focus; only the user changes navigation.
    }
    private void failed(final int generation) {
        // Post to the next UI cycle; never tear down a VLC callback in its own stack.
        handler.post(new Runnable() {
            public void run() {
                if (generation != playGeneration || !foreground) return;
                if (mode == 0 && !softwareRetry) play(index, true);
                else unavailable("地址失效、网络或格式可能不兼容");
            }
        });
    }
    private void loadInspectionHealth() {
        if(prefs.getInt("inspectionSchema",0)!=SourceInspector.SCHEMA){inspectionStatus.setText("将按新标准深测；旧版小样本记录不作优选依据");return;}
        inspectionSummary=prefs.getString("inspectionSummary","尚未巡检");
        inspectionStatus.setText(inspectionSummary);
        try {
            org.json.JSONObject saved=new org.json.JSONObject(prefs.getString("inspectionHealth","{}"));
            Iterator<String> keys=saved.keys();while(keys.hasNext()){
                String url=keys.next();org.json.JSONObject item=saved.getJSONObject(url);SourceInspector.Health h=new SourceInspector.Health();
                h=readHealth(item);channelHealth.put(url,h);
            }
        }catch(Exception ignored) { }
    }
    private void cancelInspection() {
        inspectionGeneration++;
        if(inspector!=null){inspector.cancel();inspector=null;inspectionStatus.setText("巡检已停止；原列表保留");}
    }
    private String checkpointKey(){return "inspectionCheckpoints:v2:"+source;}
    private String candidateFile(){return "inspection-candidates-"+Integer.toHexString(source.hashCode())+".m3u";}
    private String routeDirectory(){return "inspection-routes-v2-"+Integer.toHexString(source.hashCode());}
    private static SourceInspector.Health readHealth(org.json.JSONObject j){
        SourceInspector.Health h=new SourceInspector.Health();h.tested=j.optBoolean("tested",true);h.testedAt=j.optLong("at");h.responseMs=j.optLong("ms");h.bytesPerSecond=j.optLong("bps");h.headroom=j.optDouble("headroom",0);h.samples=j.optInt("samples");h.frames=j.optInt("frames");h.routes=j.optInt("routes");h.state=j.optString("state","未检测");h.ok=j.optInt("schema")==SourceInspector.SCHEMA&&j.optBoolean("ok")&&h.samples>=8&&h.frames>=30&&h.headroom>=SourceInspector.MIN_HEADROOM;return h;
    }
    private static org.json.JSONObject healthJson(SourceInspector.Health h)throws Exception{
        return new org.json.JSONObject().put("schema",SourceInspector.SCHEMA).put("ok",h.ok).put("tested",h.tested).put("at",h.testedAt).put("ms",h.responseMs).put("bps",h.bytesPerSecond).put("headroom",h.headroom).put("samples",h.samples).put("frames",h.frames).put("routes",h.routes).put("state",h.state);
    }
    private HashMap<String,SourceInspector.RouteRecord> routeRecords(String directory,int token){
        HashMap<String,SourceInspector.RouteRecord> records=new HashMap<String,SourceInspector.RouteRecord>();File[] files=new File(getFilesDir(),directory).listFiles();if(files==null)return records;
        for(File file:files){if(token!=inspectionGeneration||!inspectionForeground)break;if(!file.getName().endsWith(".bin")||file.length()>65536)continue;try(ObjectInputStream in=new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))){String id=in.readUTF();Object value=in.readObject();if(value instanceof SourceInspector.RouteRecord){SourceInspector.RouteRecord r=(SourceInspector.RouteRecord)value;if(SourceInspector.fresh(r.startedAt,System.currentTimeMillis()))records.put(id,r);else file.delete();}}catch(Exception ignored){}if(records.size()>=5000)break;}
        return records;
    }
    private void writeRoute(String directory,String id,SourceInspector.RouteRecord record)throws Exception{
        File folder=new File(getFilesDir(),directory);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法保存检测断点");byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes("UTF-8"));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.US,"%02x",b&255));File dest=new File(folder,hex+".bin"),temp=new File(folder,hex+".tmp");
        try(ObjectOutputStream out=new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))){out.writeUTF(id);out.writeObject(record);}if(!temp.renameTo(dest)){temp.delete();throw new IOException("断点保存失败");}
    }
    private HashMap<String,SourceInspector.Entry> checkpoints(){
        HashMap<String,SourceInspector.Entry> rows=new HashMap<String,SourceInspector.Entry>();
        try{org.json.JSONObject saved=new org.json.JSONObject(prefs.getString(checkpointKey(),"{}"));Iterator<String> keys=saved.keys();while(keys.hasNext()){String key=keys.next();org.json.JSONObject j=saved.getJSONObject(key);if(j.optInt("schema")!=SourceInspector.SCHEMA)continue;rows.put(key,new SourceInspector.Entry(new Playlist.Channel(j.getString("name"),j.getString("url"),j.optString("group")),readHealth(j)));}}catch(Exception ignored){}return rows;
    }
    private void startInspection(boolean manual) {
        if(destroyed||!inspectionForeground||channels.isEmpty())return;
        if(inspector!=null){
            if(manual)new AlertDialog.Builder(this).setTitle("直播源巡检中").setMessage(inspectionStatus.getText()+"\n仅在应用前台检测，不打断当前播放。")
                .setPositiveButton("继续巡检",null).setNegativeButton("停止巡检",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){cancelInspection();}}).show();
            return;
        }
        final ArrayList<Playlist.Channel> original=new ArrayList<Playlist.Channel>(channels),cached=new ArrayList<Playlist.Channel>();
        final String scanSource=source;final int listGeneration=loadGeneration,token=++inspectionGeneration;
        if(PUBLIC_LIST.equals(source))try(InputStream in=getAssets().open("public-candidates-v3.m3u")){cached.addAll(Playlist.parse(Playlist.read(in),PUBLIC_LIST));}catch(Exception ignored){}
        try(FileInputStream in=openFileInput(candidateFile())){cached.addAll(Playlist.parse(Playlist.read(in),PUBLIC_LIST));}catch(Exception ignored){}
        final HashMap<String,SourceInspector.Entry> saved=checkpoints();
        boolean restored=false;int selected=list.getSelectedItemPosition();
        for(int i=0;i<original.size();i++){Playlist.Channel c=original.get(i);SourceInspector.Entry e=saved.get(SourceInspector.key(c.name));if(e!=null&&SourceInspector.fresh(e.health.testedAt,System.currentTimeMillis())){Playlist.Channel recovered=new Playlist.Channel(c.name,e.channel.url,c.group);original.set(i,recovered);channels.set(i,recovered);channelHealth.put(recovered.url,e.health);restored=true;}}
        if(restored){updateList();if(selected>=0)list.setSelection(selected);if(index>=0)list.setItemChecked(index,true);}
        boolean due=false;long now=System.currentTimeMillis();for(Playlist.Channel c:original){SourceInspector.Entry e=saved.get(SourceInspector.key(c.name));if(e==null||!SourceInspector.fresh(e.health.testedAt,now))due=true;}
        for(Playlist.Channel c:cached){SourceInspector.Entry e=saved.get(SourceInspector.key(c.name));if(e==null||!SourceInspector.fresh(e.health.testedAt,now))due=true;}
        final String feedKey="inspectionFeedAt:"+source,recordKey=checkpointKey(),cacheFile=candidateFile();
        boolean refresh=Playlist.isHttp(source)&&!SourceInspector.fresh(prefs.getLong(feedKey,0),now);
        if(!due&&!refresh){inspectionStatus.setText("12小时内已有检测记录，已跳过；下次进入时检查到期项目");return;}
        final String[] feeds=!refresh?new String[0]:PUBLIC_LIST.equals(source)?SourceInspector.PUBLIC_FEEDS:Playlist.isHttp(source)?new String[]{source}:new String[0];
        final HashSet<String> priority=new HashSet<String>();
        for(Playlist.Channel c:original){SourceInspector.Health h=channelHealth.get(c.url);if(h==null||!h.tested)priority.add(SourceInspector.key(c.name));}
        final SourceInspector running=new SourceInspector(priority,saved,Collections.<String,SourceInspector.RouteRecord>emptyMap(),new MediaSampleDecoder(this),player==null?3:1);inspector=running;inspectionManual=manual;
        final String routeDir=routeDirectory();
        final long budget=TimeUnit.HOURS.toMillis(4);
        inspectionStatus.setText("前台深测：两轮完整样本、1.5倍余量和30帧解码；可停止并续测");
        if(manual)Toast.makeText(this,"已开始前台巡检，可继续选台",Toast.LENGTH_SHORT).show();
        final java.util.concurrent.atomic.AtomicLong lastProgress=new java.util.concurrent.atomic.AtomicLong();
        Thread thread=new Thread(new Runnable(){public void run(){
            try{
                running.restoreRoutes(routeRecords(routeDir,token));
                final SourceInspector.Result result=running.inspect(original,cached,feeds,budget,new SourceInspector.Listener(){
                    public void sampled(String id,SourceInspector.RouteRecord record){if(token!=inspectionGeneration||!inspectionForeground)return;try{writeRoute(routeDir,id,record);}catch(Exception e){android.util.Log.w("SimpleLiveTV","Could not persist route checkpoint",e);}}
                    public void completed(SourceInspector.Entry entry){
                        synchronized(prefs){if(token!=inspectionGeneration||!inspectionForeground)return;try{
                            org.json.JSONObject records=new org.json.JSONObject(prefs.getString(recordKey,"{}"));SourceInspector.Health h=entry.health;
                            records.put(SourceInspector.key(entry.channel.name),healthJson(h).put("name",entry.channel.name).put("url",entry.channel.url).put("group",entry.channel.group));
                            prefs.edit().putString(recordKey,records.toString()).commit();
                        }catch(Exception ignored){}}
                        final SourceInspector.Entry completed=entry;handler.post(new Runnable(){public void run(){if(token!=inspectionGeneration||!inspectionForeground||!source.equals(scanSource))return;int selected=list.getSelectedItemPosition();for(int i=0;i<channels.size();i++){Playlist.Channel c=channels.get(i);if(SourceInspector.key(c.name).equals(SourceInspector.key(completed.channel.name))){Playlist.Channel updated=new Playlist.Channel(c.name,completed.channel.url,c.group);channels.set(i,updated);channelHealth.put(updated.url,completed.health);}}updateList();if(selected>=0)list.setSelection(selected);if(index>=0)list.setItemChecked(index,true);}});
                    }
                    public void candidates(List<Playlist.Channel> rows,int refreshed){if(token!=inspectionGeneration||!inspectionForeground)return;try{writePlaylist(cacheFile,rows);if(feeds.length>0)prefs.edit().putLong(feedKey,System.currentTimeMillis()).commit();}catch(Exception ignored){}}
                    public void progress(final int done,final int total,final int usable){
                    long now=SystemClock.elapsedRealtime(),previous=lastProgress.get();if(done!=total&&(now-previous<500||!lastProgress.compareAndSet(previous,now)))return;
                    handler.post(new Runnable(){public void run(){if(token==inspectionGeneration&&!destroyed)inspectionStatus.setText("前台深测 "+done+"/"+total+" · 已选合格最优源 "+usable+" 台");}});
                }});
                handler.post(new Runnable(){public void run(){
                    if(destroyed||token!=inspectionGeneration||listGeneration!=loadGeneration||!source.equals(scanSource))return;
                    inspector=null;applyInspection(result);
                }});
            }catch(Exception error){handler.post(new Runnable(){public void run(){if(token==inspectionGeneration&&!destroyed){inspector=null;inspectionStatus.setText("巡检未完成；原列表保留，可手动重试");}}});}
        }},"live-source-inspection");thread.setDaemon(true);thread.start();
    }
    private void writePlaylist(String filename,List<Playlist.Channel> rows)throws Exception {
        File temp=new File(getFilesDir(),filename+".tmp"),dest=new File(getFilesDir(),filename);
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(SourceInspector.playlist(rows));}
        if(!temp.renameTo(dest)){temp.delete();throw new IOException("cache replacement");}
    }
    private void applyInspection(SourceInspector.Result result) {
        // Original channels keep their identity/order and URL if every candidate fails.
        int selected=list.getSelectedItemPosition();String selectedKey=selected>=0&&selected<channels.size()?SourceInspector.key(channels.get(selected).name):null;String playingKey=index>=0&&index<channels.size()?SourceInspector.key(channels.get(index).name):null;ArrayList<Playlist.Channel> revised=new ArrayList<Playlist.Channel>();
        channelHealth.clear();org.json.JSONObject health=new org.json.JSONObject();
        for(SourceInspector.Entry entry:result.entries){
            revised.add(entry.channel);channelHealth.put(entry.channel.url,entry.health);
            try{health.put(entry.channel.url,healthJson(entry.health));}catch(Exception ignored){}
        }
        if(!revised.isEmpty()){
            channels.clear();channels.addAll(revised);for(int i=0;i<channels.size();i++){String k=SourceInspector.key(channels.get(i).name);if(k.equals(playingKey))index=i;if(k.equals(selectedKey))selected=i;}updateList();
            if(index>=0)list.setItemChecked(index,true);if(selected>=0)list.setSelection(Math.min(selected,channels.size()-1));
        }
        String time=android.text.format.DateFormat.format("MM-dd HH:mm",new java.util.Date()).toString();
        inspectionSummary=time+" 深测：最优源 "+result.usable+" / 已测 "+result.checked+" 台"+(result.partial?"，其余待续测":"")+"\n两轮8样本、最差余量≥1.5倍、解码≥30帧；不是长期流畅保证";
        try{writePlaylist("playlist.m3u",channels);writePlaylist(candidateFile(),result.candidates);}
        catch(Exception e){inspectionSummary+="\n本次结果未能保存，下次启动将重新巡检";}
        prefs.edit().putInt("inspectionSchema",SourceInspector.SCHEMA).putString("inspectionSummary",inspectionSummary).putString("inspectionHealth",health.toString()).apply();
        inspectionStatus.setText(inspectionSummary);
        // Do not restart player, alter index, open the panel, or steal focus here.
    }
    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("播放器设置")
            .setItems(new String[]{"导入网络 M3U / TXT 列表", "导入本地播放列表", "直接播放 HTTP/HTTPS 视频地址", "解码方式：" + MODES[mode], "恢复随包频道列表", "重新加载当前列表", "播放演示片", "关于与许可证", "立即巡检并更新直播源", "进入时检查12小时到期项："+(prefs.getBoolean("autoInspect",true)?"开启":"关闭")}, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    switch (which) {
                        case 0: inputUrl(false); break;
                        case 1:
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
                            try { startActivityForResult(intent, FILE_PICKER); } catch (ActivityNotFoundException e) { setState("系统没有文件选择器，请使用网络列表"); }
                            break;
                        case 2: inputUrl(true); break;
                        case 3: chooseMode(); break;
                        case 4: loadBundled(); break;
                        case 5: if (Playlist.isHttp(source)) loadNetwork(source); else setState("请先导入网络列表"); break;
                        case 6:
                            cancelInspection();source="";
                            disposePlayer(); channels.clear(); channels.add(new Playlist.Channel("演示片 · Big Buck Bunny（非直播）", DEMO, "测试"));
                            index = -1; updateList(); play(0, false); showPanel(false); break;
                        case 7:
                            new AlertDialog.Builder(MainActivity.this).setTitle("千秋电视 1.3.0")
                                .setMessage("学习用途与免责声明：仅用于技术学习和交流，请仅访问有权使用的内容，遵守法律、源站条款及第三方许可；公开地址和检测通过不代表传播授权。不托管视频，不保证长期可用。\n\n面向 Android 5.0+ 的轻量播放器。\n\n解码内核：VideoLAN LibVLC 3.6.5（LGPL 2.1）。\n源码与许可证随 APK 一同提供。\nhttps://code.videolan.org/videolan/libvlcjni\n\n公开候选：iptv-org、YueChan及本版新增的六个公开项目，详情见GitHub仓库。频道可用性由源站及网络决定。请仅导入你有权访问的播放地址。\n\n软解可能受电视CPU性能限制，不能保证4K流畅。")
                                .setPositiveButton("知道了", null).show(); break;
                        case 8: startInspection(true);break;
                        case 9: boolean enabled=!prefs.getBoolean("autoInspect",true);prefs.edit().putBoolean("autoInspect",enabled).apply();if(enabled)startInspection(false);else cancelInspection();break;
                    }
                }
            }).setNegativeButton("返回", null).show();
    }
    private void chooseMode() {
        new AlertDialog.Builder(this).setTitle("解码方式").setSingleChoiceItems(MODES, mode, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                mode = which; prefs.edit().putInt("mode", mode).apply(); d.dismiss();
                if (index >= 0) play(index, false); else setState("已切换解码方式");
            }
        }).setNegativeButton("取消", null).show();
    }
    private void inputUrl(final boolean direct) {
        final EditText input = new EditText(this); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…"); if (!direct) input.setText(source);
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(dp(20), dp(8), dp(20), dp(8)); wrap.addView(input, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(direct ? "直接播放地址" : "网络播放列表")
            .setView(wrap).setPositiveButton("打开", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String url = input.getText().toString().trim();
                        if (!Playlist.isHttp(url)) { input.setError("请输入有效HTTP/HTTPS地址"); return; }
                        dialog.dismiss();
                        if (direct) {
                            cancelInspection();source="";
                            disposePlayer(); channels.clear(); channels.add(new Playlist.Channel("直接播放", url, "")); index = -1;
                            updateList(); play(0, false); showPanel(false);
                        } else loadNetwork(url);
                    }
                });
            }
        }); dialog.show();
    }
    private void loadNetwork(final String address) {
        cancelInspection();
        if (PUBLIC_LIST.equals(address)) { loadBundled(); return; }
        final int generation = ++loadGeneration; setState("正在下载列表…");
        worker.execute(new Runnable() {
            public void run() {
                try { final byte[] bytes = Playlist.download(address); final List<Playlist.Channel> parsed = Playlist.parse(bytes, address);
                    loaded(bytes, parsed, address, generation);
                } catch (final Exception error) { loadError(error, generation); }
            }
        });
    }
    private void loadBundled() {
        cancelInspection();
        final int generation = ++loadGeneration;
        setState("正在读取电脑筛选后的频道列表…");
        worker.execute(new Runnable() {
            public void run() {
                try (InputStream input = getAssets().open("public-cn.m3u")) {
                    byte[] bytes = Playlist.read(input);
                    loaded(bytes, Playlist.parse(bytes, PUBLIC_LIST), PUBLIC_LIST, generation);
                } catch (Exception error) { loadError(error, generation); }
            }
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FILE_PICKER || result != RESULT_OK || data == null || data.getData() == null) return;
        cancelInspection();
        final Uri uri = data.getData(); final int generation = ++loadGeneration; setState("正在读取列表…");
        worker.execute(new Runnable() {
            public void run() {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("文件无法读取");
                    byte[] bytes = Playlist.read(input); List<Playlist.Channel> parsed = Playlist.parse(bytes, ""); loaded(bytes, parsed, "", generation);
                } catch (Exception error) { loadError(error, generation); }
            }
        });
    }
    private void loaded(final byte[] bytes, final List<Playlist.Channel> parsed, final String address, final int generation) {
        handler.post(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed() || generation != loadGeneration) return;
                cancelInspection();pendingPlay=false;disposePlayer(); channels.clear();channels.addAll(parsed);channelHealth.clear(); index = -1; source = address;
                prefs.edit().putString("source", address).remove("lastIndex").apply();
                if (PUBLIC_LIST.equals(address)) prefs.edit().putInt("bundleRevision", BUNDLE_REVISION).apply();
                try (FileOutputStream output = openFileOutput("playlist.m3u", MODE_PRIVATE)) { output.write(bytes); }
                catch (IOException e) { android.util.Log.w("SimpleLiveTV", "Could not cache playlist", e); }
                updateList(); setState("已导入 " + channels.size() + " 个频道，按确定选台"); showPanel(true); list.setSelection(0);
            }
        });
    }
    private void loadError(final Exception error, final int generation) {
        handler.post(new Runnable() { public void run() {
            if (!isDestroyed() && generation == loadGeneration) setState("导入失败：" + error.getMessage() + "；原列表保留");
        }});
    }
    private void loadCache() {
        try (FileInputStream input = openFileInput("playlist.m3u")) { channels.addAll(Playlist.parse(Playlist.read(input), source)); updateList(); }
        catch (Exception ignored) { }
    }
    @Override protected void onStart() {
        super.onStart(); foreground = true;
        showPanel(true);
        if (index < 0 && !channels.isEmpty()) list.setSelection(0);

    }
    @Override protected void onResume(){super.onResume();inspectionForeground=true;if(prefs.getBoolean("autoInspect",true))startInspection(false);}
    @Override protected void onPause(){inspectionForeground=false;cancelInspection();super.onPause();}
    @Override protected void onStop() { foreground = false; pendingPlay = false;cancelInspection(); disposePlayer(); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; pendingPlay = false; loadGeneration++;
        handler.removeCallbacks(hideHint); worker.shutdownNow();
        releaseVlcWhenIdle(); super.onDestroy();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_MENU) { if (event.getRepeatCount() == 0) showSettings(); return true; }
        if (key == KeyEvent.KEYCODE_BACK) {
            if (panel.getVisibility() == View.VISIBLE) {
                if (index < 0) { finish(); return true; }
                showPanel(false);
            } else showPanel(true);
            return true;
        }
        if (panel.getVisibility() != View.VISIBLE) {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { showPanel(true); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getRepeatCount() == 0) play(index + (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1), false); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (event.getRepeatCount() == 0) chooseMode(); return true; }
        }
        return super.onKeyDown(key, event);
    }
}
