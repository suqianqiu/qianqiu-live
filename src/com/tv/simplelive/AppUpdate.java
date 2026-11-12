package com.tv.simplelive;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.net.ssl.HttpsURLConnection;

/** Small self-update client. No account credentials or external storage are used. */
final class AppUpdate {
    static final String API="https://api.github.com/repos/suqianqiu/qianqiu-live/releases/latest";
    static final String DOWNLOAD="https://github.com/suqianqiu/qianqiu-live/releases/download/";
    static final long MAX_APK=512L*1024*1024;
    interface ConnectionFactory { HttpURLConnection open(URL address)throws Exception; }
    interface Clock { long now(); }
    interface Listener { void changed(State state); }
    enum Phase { IDLE,CHECKING,CURRENT,AVAILABLE,DOWNLOADING,PAUSING,PAUSED,VERIFYING,READY,INSTALLING,ERROR }
    static final class Release {
        final String version,url,sha,notes,name;final long size;
        Release(String version,String url,String sha,String notes,String name,long size){this.version=version;this.url=url;this.sha=sha;this.notes=notes;this.name=name;this.size=size;}
        JSONObject json()throws JSONException{return new JSONObject().put("version",version).put("url",url).put("sha",sha).put("notes",notes).put("name",name).put("size",size);}
        static Release read(JSONObject j)throws Exception{Release r=new Release(j.getString("version"),j.getString("url"),j.getString("sha"),j.optString("notes",""),j.getString("name"),j.getLong("size"));validateRelease(r);return r;}
    }
    static final class State {
        final Phase phase;final Release release;final String message,current;final long bytes,checked;final boolean newer,ready;
        State(Phase p,Release r,String m,String c,long b,long checked,boolean newer,boolean ready){phase=p;release=r;message=m;current=c;bytes=b;this.checked=checked;this.newer=newer;this.ready=ready;}
        boolean busy(){return phase==Phase.CHECKING||phase==Phase.DOWNLOADING||phase==Phase.PAUSING||phase==Phase.VERIFYING||phase==Phase.INSTALLING;}
    }
    private static AppUpdate instance;
    static synchronized AppUpdate get(Context context){if(instance==null)instance=new AppUpdate(context);return instance;}
    final Context context;final android.content.SharedPreferences prefs;final File directory,part,apk;
    private final ConnectionFactory connections;private final Clock clock;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    private Phase phase=Phase.IDLE;private Release release;private String message="";private long bytes;
    private boolean working;private volatile boolean paused;private volatile HttpURLConnection active;
    private WeakReference<UpdateActivity> host=new WeakReference<>(null);
    private AppUpdate(Context context){this(context,address->{CompatTls.initialize(context);return(HttpURLConnection)address.openConnection();},System::currentTimeMillis);}
    AppUpdate(Context context,ConnectionFactory connections,Clock clock){
        this.context=context.getApplicationContext();this.connections=connections;this.clock=clock;
        prefs=this.context.getSharedPreferences("appUpdate",0);directory=new File(this.context.getCacheDir(),"app-update");directory.mkdirs();part=new File(directory,"package.part");apk=new File(directory,"package.apk");
        try{String saved=prefs.getString("release","");if(!saved.isEmpty())release=Release.read(new JSONObject(saved));}catch(Exception ignored){}
        if(release!=null&&newer(release.version,currentVersion())){bytes=part.length();phase=apk.length()==release.size?Phase.READY:bytes>0?Phase.PAUSED:Phase.AVAILABLE;}else if(release!=null){phase=Phase.CURRENT;clearPackages();}
        if(prefs.getInt("installSession",-1)>=0){phase=Phase.INSTALLING;recoverInstall();}
    }
    static String day(long now){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);return f.format(new Date(now));}
    String currentVersion(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception e){return "0.0.0";}}
    static boolean newer(String offered,String installed){int[] a=version(offered),b=version(installed);for(int i=0;i<3;i++)if(a[i]!=b[i])return a[i]>b[i];return false;}
    private static int[] version(String value){if(value==null||!value.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))throw new IllegalArgumentException("版本号无效");String[] p=value.split("\\.");return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};}
    static boolean preferTv(String[] abis){return abis.length>0&&(abis[0].equals("arm64-v8a")||abis[0].equals("armeabi-v7a"));}
    static Release parse(JSONObject json,boolean tv)throws Exception{
        if(json.optBoolean("draft",true)||json.optBoolean("prerelease",true))throw new IOException("不是正式版本");
        String tag=json.getString("tag_name");if(!tag.startsWith("v"))throw new IOException("版本信息无效");String v=tag.substring(1);version(v);
        String name="QianqiuLive-"+v+".apk";Release found=null;JSONArray assets=json.getJSONArray("assets");
        for(int i=0;i<assets.length();i++){JSONObject a=assets.getJSONObject(i);if(!name.equals(a.optString("name")))continue;if(found!=null||!"uploaded".equals(a.optString("state")))throw new IOException("安装包信息无效");String digest=a.optString("digest","");if(!digest.matches("sha256:[a-fA-F0-9]{64}"))throw new IOException("缺少安装包校验信息");found=new Release(v,a.getString("browser_download_url"),digest.substring(7).toLowerCase(Locale.US),notes(json.optString("body","")),name,a.getLong("size"));}
        if(found==null)throw new IOException("未找到适合此设备的安装包");validateRelease(found);return found;
    }
    private static String notes(String body){StringBuilder out=new StringBuilder();for(String line:body.split("\\r?\\n")){String s=line.trim();if(s.startsWith("- ")&&!s.contains("http")&&!s.contains(".apk")&&!s.contains(".zip")&&!s.contains(".jar")){if(out.length()>0)out.append('\n');out.append("• ").append(s.substring(2).replace("**",""));if(out.length()>320)break;}}return out.length()==0?"改进使用体验与稳定性。":out.substring(0,Math.min(420,out.length()));}
    private static void validateRelease(Release r)throws Exception{version(r.version);if(r.size<=0||r.size>MAX_APK||!r.sha.matches("[a-f0-9]{64}"))throw new IOException("安装包信息无效");String a="QianqiuLive-"+r.version+".apk";if(!r.name.equals(a)||!r.url.equals(DOWNLOAD+"v"+r.version+"/"+r.name))throw new IOException("安装包来源无效");}
    synchronized State state(){boolean n=release!=null&&newer(release.version,currentVersion());return new State(phase,release,message,currentVersion(),bytes,prefs.getLong("checked",0),n,n&&apk.length()==release.size);}
    void add(Listener listener){listeners.addIfAbsent(listener);ui.post(()->listener.changed(state()));}
    void remove(Listener listener){listeners.remove(listener);}
    void attach(UpdateActivity activity){host=new WeakReference<>(activity);recoverInstall();}
    void detach(UpdateActivity activity){if(host.get()==activity)host.clear();}
    private void publish(){ui.post(()->{State value=state();for(Listener listener:listeners)listener.changed(value);});}
    private synchronized void status(Phase value,String text){phase=value;message=text;publish();}
    boolean shouldPrompt(){State s=state();return (s.phase==Phase.AVAILABLE||s.phase==Phase.PAUSED||s.phase==Phase.READY)&&s.newer&&!day(clock.now()).equals(prefs.getString("promptedDay",""));}
    void markPrompted(){prefs.edit().putString("promptedDay",day(clock.now())).apply();}
    synchronized void check(boolean manual){
        recoverInstall();
        if(working||phase==Phase.INSTALLING)return;long now=clock.now();
        if(!manual&&day(now).equals(prefs.getString("attemptDay","")))return;
        working=true;prefs.edit().putLong("attempt",now).putString("attemptDay",day(now)).commit();status(Phase.CHECKING,"正在检查更新…");
        work.execute(()->{try{HttpURLConnection c=open(API,Collections.emptyMap());byte[] data;try{if(c.getResponseCode()!=200)throw new IOException("检查失败");try(InputStream in=c.getInputStream()){data=read(in,256*1024);}}finally{c.disconnect();}
            Release next=parse(new JSONObject(new String(data,"UTF-8")),preferTv(Build.SUPPORTED_ABIS));
            synchronized(this){if(release==null||!release.sha.equals(next.sha))clearPackages();release=next;prefs.edit().putString("release",next.json().toString()).putLong("checked",clock.now()).putString("checkedDay",day(clock.now())).remove("failedAt").apply();bytes=part.length();if(!newer(next.version,currentVersion())){clearPackages();status(Phase.CURRENT,"当前已是最新版本");}else status(apk.length()==next.size?Phase.READY:bytes>0?Phase.PAUSED:Phase.AVAILABLE,"发现新版本 "+next.version);}
        }catch(Exception e){prefs.edit().putLong("failedAt",clock.now()).apply();synchronized(this){status(release!=null&&newer(release.version,currentVersion())?(apk.length()==release.size?Phase.READY:part.length()>0?Phase.PAUSED:Phase.AVAILABLE):Phase.ERROR,"暂时无法检查更新，请稍后重试");}}
        finally{synchronized(this){working=false;}publish();}});
    }
    private HttpURLConnection open(String address,Map<String,String> headers)throws Exception{
        URL url=new URL(address);for(int i=0;i<6;i++){
            String h=url.getHost().toLowerCase(Locale.US);if(!url.getProtocol().equals("https")||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443||!Arrays.asList("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com").contains(h))throw new IOException("下载来源无效");
            HttpURLConnection c=connections.open(url);c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setUseCaches(false);c.setRequestProperty("User-Agent","QianqiuLive-Update/"+currentVersion());c.setRequestProperty("Accept-Encoding","identity");for(Map.Entry<String,String> row:headers.entrySet())c.setRequestProperty(row.getKey(),row.getValue());if(c instanceof HttpsURLConnection)((HttpsURLConnection)c).setSSLSocketFactory(CompatTls.factory());
            int code;try{code=c.getResponseCode();}catch(Exception e){c.disconnect();throw e;}if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("下载地址无效");url=new URL(url,location);continue;}return c;
        }throw new IOException("下载跳转过多");
    }
    private static byte[] read(InputStream in,int cap)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>cap)throw new IOException("版本信息过大");out.write(b,0,n);}return out.toByteArray();}
    private void space(long required)throws IOException{if(new StatFs(directory.getPath()).getAvailableBytes()<required+16L*1024*1024)throw new IOException("SPACE");}
    synchronized void download(){
        if(working||release==null||!newer(release.version,currentVersion()))return;Release target=release;working=true;paused=false;status(Phase.DOWNLOADING,"正在下载更新…");
        work.execute(()->{try{
            if(apk.length()==target.size){status(Phase.VERIFYING,"正在校验安装包…");validateApk(apk,target);status(Phase.READY,"安装包已准备好");return;}
            if(part.length()==target.size){status(Phase.VERIFYING,"正在校验安装包…");validateApk(part,target);if(!part.renameTo(apk))throw new IOException("SAVE");synchronized(this){bytes=target.size;}status(Phase.READY,"安装包已准备好");return;}
            long have=part.length();if(have>target.size){erase(part);have=0;}space(target.size-have);Map<String,String> headers=new HashMap<>();if(have>0)headers.put("Range","bytes="+have+"-");HttpURLConnection c=open(target.url,headers);active=c;
            try{int code=c.getResponseCode();if(code==200)have=0;else if(code==206){String range=c.getHeaderField("Content-Range");java.util.regex.Matcher m=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range==null?"":range);if(!m.matches()||Long.parseLong(m.group(1))!=have||Long.parseLong(m.group(3))!=target.size||Long.parseLong(m.group(2))<have||Long.parseLong(m.group(2))>=target.size)throw new IOException("RANGE");}else throw new IOException("HTTP");
                synchronized(this){bytes=have;}publish();try(InputStream in=c.getInputStream();RandomAccessFile out=new RandomAccessFile(part,"rw")){out.setLength(have);out.seek(have);byte[] b=new byte[65536];long last=0;for(int n;!paused&&(n=in.read(b))!=-1;){if(have+n>target.size)throw new IOException("SIZE");out.write(b,0,n);have+=n;synchronized(this){bytes=have;}if(SystemClock.elapsedRealtime()-last>250){publish();last=SystemClock.elapsedRealtime();}}out.getFD().sync();}
            }finally{active=null;c.disconnect();}
            if(paused){status(Phase.PAUSED,"下载已暂停，可稍后继续");return;}if(part.length()!=target.size)throw new IOException("INCOMPLETE");status(Phase.VERIFYING,"正在校验安装包…");validateApk(part,target);if(!part.renameTo(apk))throw new IOException("SAVE");status(Phase.READY,"安装包已准备好，点击即可升级");
        }catch(Exception e){if(paused)status(Phase.PAUSED,"下载已暂停，可稍后继续");else if(e instanceof BadApk){erase(part);erase(apk);synchronized(this){bytes=0;}status(Phase.AVAILABLE,"安装包校验未通过，请重新下载");}else status(part.length()>0?Phase.PAUSED:Phase.AVAILABLE,"SPACE".equals(e.getMessage())?"存储空间不足，请清理后重试":"下载未完成，请稍后继续");}
        finally{synchronized(this){working=false;}publish();}});
    }
    synchronized void pause(){if(phase!=Phase.DOWNLOADING)return;paused=true;status(Phase.PAUSING,"正在暂停下载…");HttpURLConnection c=active;if(c!=null){Thread close=new Thread(c::disconnect,"update-pause");close.setDaemon(true);close.start();}}
    static final class BadApk extends IOException {BadApk(){super("Invalid update package");}}
    private static String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format(Locale.US,"%02x",b&255));return h.toString();}
    private static long code(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    private static Set<String> signatures(PackageInfo p)throws Exception{android.content.pm.Signature[] list=Build.VERSION.SDK_INT>=28&&p.signingInfo!=null?p.signingInfo.getApkContentsSigners():p.signatures;if(list==null||list.length==0)throw new BadApk();Set<String> set=new HashSet<>();for(android.content.pm.Signature s:list)set.add(android.util.Base64.encodeToString(s.toByteArray(),android.util.Base64.NO_WRAP));return set;}
    long validateApk(File file,Release target)throws Exception{
        if(file.length()!=target.size||!hash(file).equals(target.sha))throw new BadApk();PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;PackageInfo installed=pm.getPackageInfo(context.getPackageName(),flags),offered=pm.getPackageArchiveInfo(file.getPath(),flags);
        if(offered==null||!context.getPackageName().equals(offered.packageName)||!target.version.equals(offered.versionName)||code(offered)<=code(installed)||!signatures(installed).equals(signatures(offered)))throw new BadApk();
        Set<String> included=new HashSet<>();try(ZipFile z=new ZipFile(file)){for(Enumeration<? extends ZipEntry> e=z.entries();e.hasMoreElements();){String n=e.nextElement().getName();if(n.startsWith("lib/")&&n.endsWith(".so"))included.add(n.split("/")[1]);}}boolean compatible=included.isEmpty();for(String abi:Build.SUPPORTED_ABIS)compatible|=included.contains(abi);if(!compatible)throw new BadApk();return code(offered);
    }
    boolean canInstall(){if(Build.VERSION.SDK_INT>=26)return context.getPackageManager().canRequestPackageInstalls();try{return Settings.Secure.getInt(context.getContentResolver(),"install_non_market_apps",0)==1;}catch(Exception e){return false;}}
    synchronized void install(){
        if(working||phase==Phase.INSTALLING||release==null||apk.length()!=release.size||!canInstall())return;Release target=release;working=true;status(Phase.INSTALLING,"正在准备安装，请稍候…");
        work.execute(()->{int id=-1;boolean committed=false;try{long targetCode=validateApk(apk,target);space(target.size);PackageInstaller installer=context.getPackageManager().getPackageInstaller();PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(context.getPackageName());params.setSize(target.size);if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            id=installer.createSession(params);String nonce=UUID.randomUUID().toString();Intent callback=new Intent(context,UpdateInstallReceiver.class).setAction(context.getPackageName()+".UPDATE_RESULT").putExtra("nonce",nonce);int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;PendingIntent pending=PendingIntent.getBroadcast(context,id,callback,flags);
            prefs.edit().putInt("installSession",id).putString("installNonce",nonce).putLong("installStarted",clock.now()).putLong("installTarget",targetCode).commit();
            try(PackageInstaller.Session session=installer.openSession(id)){try(InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite("base.apk",0,target.size)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);session.fsync(out);}session.commit(pending.getIntentSender());committed=true;ui.postDelayed(installTimeout,10*60_000L+100);}
        }catch(Exception e){if(id>=0&&!committed)try{context.getPackageManager().getPackageInstaller().abandonSession(id);}catch(Exception ignored){}clearInstall();status(Phase.READY,e instanceof BadApk?"安装包校验未通过，请重新下载":"SPACE".equals(e.getMessage())?"存储空间不足，请清理后重试":"暂时无法安装，请重试或检查电视的安装限制");if(e instanceof BadApk){erase(apk);synchronized(this){bytes=0;}status(Phase.AVAILABLE,"安装包校验未通过，请重新下载");}}
        finally{synchronized(this){working=false;}publish();}});
    }
    void result(Intent intent){
        int id=prefs.getInt("installSession",-1);if(id<0||id!=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-2)||!prefs.getString("installNonce","").equals(intent.getStringExtra("nonce")))return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent approval=intent.getParcelableExtra(Intent.EXTRA_INTENT);UpdateActivity activity=host.get();if(activity!=null&&activity.active()&&approval!=null){activity.confirmInstall(approval);return;}cancelInstall("安装需要确认，请进入版本更新后重试");return;}
        clearInstall();if(status==PackageInstaller.STATUS_SUCCESS){clearPackages();status(Phase.CURRENT,"更新已完成");}else status(Phase.READY,status==PackageInstaller.STATUS_FAILURE_ABORTED?"已取消安装，可稍后重试":status==PackageInstaller.STATUS_FAILURE_STORAGE?"存储空间不足，请清理后重试":status==PackageInstaller.STATUS_FAILURE_BLOCKED?"安装被系统阻止，请检查安装授权与电视限制":"安装未完成，可稍后重试");
    }
    void cancelInstall(String text){int id=prefs.getInt("installSession",-1);clearInstall();if(id>=0)try{context.getPackageManager().getPackageInstaller().abandonSession(id);}catch(Exception ignored){}status(Phase.READY,text);}
    private void clearInstall(){ui.removeCallbacks(installTimeout);prefs.edit().remove("installSession").remove("installNonce").remove("installStarted").remove("installTarget").commit();}
    private synchronized void recoverInstall(){
        if(phase!=Phase.INSTALLING||working)return;int id=prefs.getInt("installSession",-1);PackageInstaller installer=context.getPackageManager().getPackageInstaller();PackageInstaller.SessionInfo info=null;try{if(id>=0)info=installer.getSessionInfo(id);}catch(RuntimeException ignored){}
        boolean pending=release!=null&&newer(release.version,currentVersion());long age=clock.now()-prefs.getLong("installStarted",0);
        if(pending&&info!=null&&age>=0&&age<10*60_000L){ui.removeCallbacks(installTimeout);ui.postDelayed(installTimeout,10*60_000L-age+100);return;}
        if(info!=null)try{installer.abandonSession(id);}catch(RuntimeException ignored){}clearInstall();status(pending?(apk.length()==release.size?Phase.READY:part.length()>0?Phase.PAUSED:Phase.AVAILABLE):release!=null?Phase.CURRENT:Phase.IDLE,pending?"上次安装已中止，可重新尝试":"");
    }
    private final Runnable installTimeout=this::recoverInstall;
    private void clearPackages(){erase(part);erase(apk);bytes=0;}
    private static void erase(File file){if(file.exists())file.delete();}
}
