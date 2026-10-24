package com.tv.simplelive;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Offscreen device verification: never wakes the box or sends HDMI/remote commands. */
public final class HeadlessInspectionTest extends Instrumentation {
    private boolean engineOnly;
    private final StringBuilder log=new StringBuilder();private Activity activity;
    private void check(boolean yes,String text)throws Exception{if(!yes)throw new Exception("FAIL: "+text);log.append("PASS: ").append(text).append('\n');}
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}
    private static byte[] read(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}}
    private static void write(File file,byte[] data)throws Exception{file.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(file)){out.write(data);}}
    private static void snapshot(File root,File file,Map<String,byte[]> saved)throws Exception{File[] children=file.listFiles();if(children==null)return;for(File c:children)if(c.isDirectory())snapshot(root,c,saved);else saved.put(root.toURI().relativize(c.toURI()).getPath(),read(c));}
    private static void removeNew(File root,File file,Set<String> saved){File[] children=file.listFiles();if(children==null)return;for(File c:children)if(c.isDirectory())removeNew(root,c,saved);else if(!saved.contains(root.toURI().relativize(c.toURI()).getPath()))c.delete();}
    private final class Fixture implements Closeable {
        final ServerSocket server;final ExecutorService pool=Executors.newCachedThreadPool();final ConcurrentHashMap<String,AtomicInteger> hits=new ConcurrentHashMap<String,AtomicInteger>();final String base;
        Fixture()throws Exception{server=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort();new Thread(new Runnable(){public void run(){while(!server.isClosed())try{final Socket socket=server.accept();pool.execute(new Runnable(){public void run(){serve(socket);}});}catch(Exception e){break;}}},"deep-fixture").start();}
        int count(String prefix){int n=0;for(Map.Entry<String,AtomicInteger> e:hits.entrySet())if(e.getKey().startsWith(prefix))n+=e.getValue().get();return n;}
        void serve(Socket socket){try(Socket s=socket){s.setSoTimeout(2500);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));String first=in.readLine();if(first==null)return;String path=first.split(" ")[1];hits.putIfAbsent(path,new AtomicInteger());hits.get(path).incrementAndGet();String line;while((line=in.readLine())!=null&&!line.isEmpty()){}if(path.equals("/dead")){s.getOutputStream().write("HTTP/1.1 404 Missing\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}
            String name=path.substring(path.lastIndexOf('/')+1);byte[] bytes;if(path.startsWith("/moderate/"))Thread.sleep(100);
            if(name.equals("key.bin")){bytes=new byte[16];for(int i=0;i<16;i++)bytes[i]=(byte)i;}
            else if(name.equals("index.m3u8")||name.matches("seg[0-9]+.ts")){try(InputStream asset=getContext().getAssets().open(name);ByteArrayOutputStream data=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=asset.read(b))!=-1)data.write(b,0,n);bytes=data.toByteArray();}if(path.startsWith("/bad/")&&name.endsWith(".ts"))Arrays.fill(bytes,(byte)0x55);if(path.startsWith("/aes/")){if(name.endsWith("m3u8"))bytes=new String(bytes,"UTF-8").replace("#EXT-X-VERSION:3","#EXT-X-VERSION:3\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"").getBytes("UTF-8");else{byte[] key=new byte[16],iv=new byte[16];for(int i=0;i<16;i++)key[i]=(byte)i;iv[15]=(byte)Integer.parseInt(name.substring(3,6));javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.IvParameterSpec(iv));bytes=cipher.doFinal(bytes);}}}
            else throw new IOException("unknown fixture");OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(bytes);
        }catch(Exception ignored){}}
        public void close(){try{server.close();}catch(Exception ignored){}pool.shutdownNow();}
    }
    private static Playlist.Channel c(String name,String url){return new Playlist.Channel(name,url,"test");}
    private SourceInspector engine(Map<String,SourceInspector.Entry> stations,Map<String,SourceInspector.RouteRecord> routes){return new SourceInspector(Collections.<String>emptySet(),stations,routes,new MediaSampleDecoder(getTargetContext()));}
    @Override public void onCreate(Bundle args){super.onCreate(args);engineOnly=args!=null&&"true".equals(args.getString("engineOnly"));start();}
    @Override public void onStart(){Bundle result=new Bundle();Context ctx=getTargetContext();SharedPreferences prefs=ctx.getSharedPreferences("settings",0);Map<String,?> savedPrefs=new HashMap<String,Object>(prefs.getAll());Map<String,byte[]> savedFiles=new HashMap<String,byte[]>();Fixture fixture=null;boolean backedUp=false;
        try{
            snapshot(ctx.getFilesDir(),ctx.getFilesDir(),savedFiles);backedUp=true;fixture=new Fixture();final Fixture f=fixture;
            byte[] sample;try(InputStream in=getContext().getAssets().open("seg008.ts");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);sample=out.toByteArray();}
            SourceInspector.SampleInfo actual=new MediaSampleDecoder(ctx).analyze(sample,true,new SourceInspector.Stop(){public boolean stopped(){return false;}});
            check(actual.frames>=30,"Android MediaCodec really decodes at least 30 offscreen video frames: "+actual.frames);
            final ConcurrentHashMap<String,SourceInspector.RouteRecord> routeRecords=new ConcurrentHashMap<String,SourceInspector.RouteRecord>();final ConcurrentHashMap<String,SourceInspector.Entry> stations=new ConcurrentHashMap<String,SourceInspector.Entry>();
            SourceInspector.Listener persist=new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void sampled(String id,SourceInspector.RouteRecord r){routeRecords.put(id,r);}public void completed(SourceInspector.Entry e){stations.put(SourceInspector.key(e.channel.name),e);}};
            List<Playlist.Channel> original=Arrays.asList(c("央视五套·体育",f.base+"/moderate/index.m3u8"),c("CCTV5",f.base+"/dead"),c("Healthy",f.base+"/fast/index.m3u8"),c("Invalid",f.base+"/bad/index.m3u8"));
            SourceInspector.Result scan=engine(Collections.<String,SourceInspector.Entry>emptyMap(),Collections.<String,SourceInspector.RouteRecord>emptyMap()).inspect(original,Arrays.asList(c("CCTV5HD",f.base+"/fast/index.m3u8")),new String[0],45000,persist);
            check(scan.entries.size()==3&&scan.entries.get(0).channel.name.equals("央视五套·体育"),"device deduplicates equivalent station names and preserves first display name");
            SourceInspector.Entry winner=scan.entries.get(0);check(winner.health.ok&&winner.health.samples==8&&winner.health.frames>=30&&winner.health.headroom>=1.5,"real HLS source passes two rounds, eight full segments, delivery margin and native decode");
            check(winner.channel.url.equals(f.base+"/fast/index.m3u8")&&winner.health.routes==3&&f.count("/moderate/seg")>=8,"all candidate sources compared before selecting best even when original passes");
            check(!scan.entries.get(2).health.ok&&scan.entries.get(2).health.testedAt>0,"invalid media is recorded as unqualified rather than reachable");
            SourceInspector.Result encrypted=engine(Collections.<String,SourceInspector.Entry>emptyMap(),Collections.<String,SourceInspector.RouteRecord>emptyMap()).inspect(Arrays.asList(c("AES",f.base+"/aes/index.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[0],20000,null);
            check(encrypted.usable==1&&encrypted.entries.get(0).health.frames>=30,"AES-128 HLS is decrypted with media-sequence IV and actually decoded on device");
            int hits=f.count("/");SourceInspector.Result cached=engine(stations,routeRecords).inspect(original,Collections.<Playlist.Channel>emptyList(),new String[0],10000,null);check(f.count("/")==hits&&cached.usable==2,"recent successful and failed station records both skip all network work");
            final ConcurrentHashMap<String,SourceInspector.RouteRecord> resumeRecords=new ConcurrentHashMap<String,SourceInspector.RouteRecord>();final AtomicReference<SourceInspector> interrupted=new AtomicReference<SourceInspector>();interrupted.set(engine(Collections.<String,SourceInspector.Entry>emptyMap(),Collections.<String,SourceInspector.RouteRecord>emptyMap()));
            SourceInspector.Result paused=interrupted.get().inspect(Arrays.asList(c("Resume",f.base+"/resume/index.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[0],15000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void sampled(String id,SourceInspector.RouteRecord r){resumeRecords.put(id,r);if(r.round==1&&r.completedAt==0)interrupted.get().cancel();}});
            check(paused.partial&&paused.entries.get(0).health.testedAt==0,"paused deep test does not stamp incomplete station for twelve hours");
            int before=f.count("/resume/seg");SourceInspector.Result resumed=engine(Collections.<String,SourceInspector.Entry>emptyMap(),resumeRecords).inspect(Arrays.asList(c("Resume",f.base+"/resume/index.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[0],15000,null);
            check(resumed.usable==1&&f.count("/resume/seg")-before==4,"device resumes round two without redownloading four completed samples");
            // UI properties can be checked while asleep; no wake, key event or HDMI command is used.
            if(!engineOnly){
            prefs.edit().putBoolean("autoInspect",false).commit();activity=startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            final SourceInspector.RouteRecord partial=resumeRecords.values().iterator().next();final String recordId=resumeRecords.keySet().iterator().next();
            call("writeRoute",new Class<?>[]{String.class,String.class,SourceInspector.RouteRecord.class},"inspection-test-private",recordId,partial);set("inspectionForeground",true);Map<String,SourceInspector.RouteRecord> disk=(Map<String,SourceInspector.RouteRecord>)call("routeRecords",new Class<?>[]{String.class,int.class},"inspection-test-private",(Integer)field("inspectionGeneration"));set("inspectionForeground",false);
            check(disk.get(recordId).round==1&&disk.get(recordId).samples.size()==4,"app private checkpoint file preserves completed round and identities");
            call("startInspection",new Class<?>[]{boolean.class},true);check(field("inspector")==null,"paused app refuses automatic and manual deep inspection");
            Method encode=MainActivity.class.getDeclaredMethod("healthJson",SourceInspector.Health.class);encode.setAccessible(true);org.json.JSONObject good=(org.json.JSONObject)encode.invoke(null,winner.health);Method decode=MainActivity.class.getDeclaredMethod("readHealth",org.json.JSONObject.class);decode.setAccessible(true);
            check(((SourceInspector.Health)decode.invoke(null,good)).ok,"qualified station metrics survive persisted JSON roundtrip");good.put("samples",1);check(!((SourceInspector.Health)decode.invoke(null,good)).ok,"legacy or insufficient small sample cannot be restored as qualified");
            final AtomicReference<Throwable> uiError=new AtomicReference<Throwable>();runOnMainSync(new Runnable(){public void run(){try{
                ArrayList<Playlist.Channel> rows=(ArrayList<Playlist.Channel>)field("channels");rows.clear();rows.add(original.get(0));rows.add(original.get(1));rows.add(original.get(2));rows.add(original.get(3));set("index",2);call("updateList",new Class<?>[]{});((ListView)field("list")).setSelection(2);Object player=field("player");int panel=((View)field("panel")).getVisibility();call("applyInspection",new Class<?>[]{SourceInspector.Result.class},scan);if((Integer)field("index")!=1||((ListView)field("list")).getSelectedItemPosition()!=1||field("player")!=player||((View)field("panel")).getVisibility()!=panel)throw new Exception("selection/player/panel changed");
            }catch(Throwable e){uiError.set(e);}}});check(uiError.get()==null,"deduplication keeps selected station, player identity and navigation panel: "+uiError.get());
            }
            log.append("ENGINE ONLY: ").append(engineOnly).append('\n');
            log.append("DEVICE: ").append(Build.MODEL).append(" SDK ").append(Build.VERSION.SDK_INT).append('\n');result.putString("stream",log.toString());
        }catch(Throwable e){result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
        finally{
            if(activity!=null){try{set("inspectionForeground",false);call("cancelInspection",new Class<?>[]{});}catch(Exception ignored){}final Activity close=activity;runOnMainSync(new Runnable(){public void run(){close.finish();}});}
            if(fixture!=null)fixture.close();
            if(backedUp)try{removeNew(ctx.getFilesDir(),ctx.getFilesDir(),savedFiles.keySet());for(Map.Entry<String,byte[]> e:savedFiles.entrySet())write(new File(ctx.getFilesDir(),e.getKey()),e.getValue());}catch(Exception e){result.putString("stream",result.getString("stream","")+"ERROR restoring files: "+e);}
            SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> e:savedPrefs.entrySet()){Object v=e.getValue();if(v instanceof String)edit.putString(e.getKey(),(String)v);else if(v instanceof Boolean)edit.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)edit.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(e.getKey(),(Long)v);else if(v instanceof Float)edit.putFloat(e.getKey(),(Float)v);else if(v instanceof Set)edit.putStringSet(e.getKey(),(Set<String>)v);}edit.commit();
        }
        finish(result.getString("stream","").contains("ERROR:")?1:-1,result);
    }
}
