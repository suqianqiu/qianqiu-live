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
    private static final int BUNDLE_REVISION = 5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Playlist.Channel> channels = new ArrayList<Playlist.Channel>();
    private LibVLC vlc;
    private MediaPlayer player;
    private SurfaceView surface;
    private View videoCover;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView status, heading, hint;
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
        list.requestFocus();
    }
    private void addButton(LinearLayout row, String text, final Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(16);
        button.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } });
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }
    private void updateList() {
        names.clear();
        for (int i = 0; i < channels.size(); i++) names.add(String.format(java.util.Locale.US, "%02d  %s", i+1, channels.get(i).name));
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
        hint.setText((index >= 0 && index < channels.size() ? channels.get(index).name + "\n" : "")
            + state + (currentDecode.isEmpty() ? "" : " · " + currentDecode));
        hint.setVisibility(View.VISIBLE); handler.removeCallbacks(hideHint); handler.postDelayed(hideHint, 6000);
    }
    private void disposePlayer() {
        playGeneration++;
        videoCover.setVisibility(View.VISIBLE);
        if (player != null) {
            player.setEventListener(null);
            player.stop(); player.getVLCVout().detachViews(); player.release(); player = null;
        }
    }
    private void play(int position, boolean retrySoft) {
        if (!foreground || vlc == null || channels.isEmpty()) return;
        disposePlayer();
        index = (position + channels.size()) % channels.size();
        prefs.edit().putInt("lastIndex", index).apply();
        softwareRetry = retrySoft; seenVideo = false;
        final int generation = playGeneration;
        currentDecode = (mode == 2 || retrySoft) ? "软解" : (mode == 1 ? "硬解" : "优先硬解");
        android.util.Log.i("SimpleLiveTV", "play mode=" + mode + " softwareRetry=" + retrySoft + " generation=" + generation);
        setState(retrySoft ? "正在尝试软解" : "正在连接");
        list.setItemChecked(index, true);
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
                            setState("播放结束；可换台或重新选择"); break;
                    }
                }
            });
            Media media = new Media(vlc, Uri.parse(channels.get(index).url));
            try {
                media.setHWDecoderEnabled(mode != 2 && !retrySoft, mode == 1);
                media.addOption(":network-caching=1800");
                player.setMedia(media);
            } finally { media.release(); }
            player.play();
            handler.postDelayed(new Runnable() {
                public void run() {
                    if (generation != playGeneration || player == null || !foreground || seenVideo) return;
                    if (mode == 0 && !softwareRetry) play(index, true);
                    else { disposePlayer(); setState("20 秒未出现画面；请检查直播源/网络或尝试其他解码模式"); showPanel(true); }
                }
            }, 20000);
        } catch (Throwable error) {
            disposePlayer(); setState("播放组件错误：" + error.getClass().getSimpleName()); showPanel(true);
        }
    }
    private void failed(final int generation) {
        // Post to the next UI cycle; never tear down a VLC callback in its own stack.
        handler.post(new Runnable() {
            public void run() {
                if (generation != playGeneration || !foreground) return;
                if (mode == 0 && !softwareRetry) play(index, true);
                else { disposePlayer(); setState("无法播放；地址失效、网络或格式可能不兼容"); showPanel(true); }
            }
        });
    }
    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("播放器设置")
            .setItems(new String[]{"导入网络 M3U / TXT 列表", "导入本地播放列表", "直接播放 HTTP/HTTPS 视频地址", "解码方式：" + MODES[mode], "恢复电脑筛选后的频道列表", "重新加载当前列表", "播放演示片", "关于与许可证"}, new DialogInterface.OnClickListener() {
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
                            disposePlayer(); channels.clear(); channels.add(new Playlist.Channel("演示片 · Big Buck Bunny（非直播）", DEMO, "测试"));
                            index = -1; updateList(); play(0, false); showPanel(false); break;
                        case 7:
                            new AlertDialog.Builder(MainActivity.this).setTitle("千秋电视 1.0.5")
                                .setMessage("面向 Android 5.0+ 的轻量播放器。无账号、广告或付费弹窗。\n\n解码内核：VideoLAN LibVLC 3.6.5（LGPL 2.1）。\n源码与许可证随 APK 一同提供。\nhttps://code.videolan.org/videolan/libvlcjni\n\n公开列表：iptv-org/iptv。频道可用性由源站及网络决定。请仅导入你有权访问的播放地址。\n\n软解可能受电视CPU性能限制，不能保证4K流畅。")
                                .setPositiveButton("知道了", null).show(); break;
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
                            disposePlayer(); channels.clear(); channels.add(new Playlist.Channel("直接播放", url, "")); index = -1;
                            updateList(); play(0, false); showPanel(false);
                        } else loadNetwork(url);
                    }
                });
            }
        }); dialog.show();
    }
    private void loadNetwork(final String address) {
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
                disposePlayer(); channels.clear(); channels.addAll(parsed); index = -1; source = address;
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
    @Override protected void onStop() { foreground = false; disposePlayer(); super.onStop(); }
    @Override protected void onDestroy() {
        loadGeneration++; handler.removeCallbacksAndMessages(null); worker.shutdownNow();
        if (vlc != null) { vlc.release(); vlc = null; } super.onDestroy();
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
