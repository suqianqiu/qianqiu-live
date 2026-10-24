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

public final class InspectionTest extends Instrumentation {
    private Activity activity;private boolean real;private final StringBuilder log=new StringBuilder();
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void call(final String name,final Class<?>[] types,final Object...args){runOnMainSync(new Runnable(){public void run(){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}});}
    private void check(boolean yes,String message)throws Exception{if(!yes)throw new Exception("FAIL: "+message);log.append("PASS: ").append(message).append('\n');}
    private void waitDone()throws Exception{long end=System.currentTimeMillis()+25000;while(field("inspector")!=null&&System.currentTimeMillis()<end)Thread.sleep(100);waitForIdleSync();check(field("inspector")==null,"background inspection completes");}
    private static byte[] read(File file)throws Exception{if(!file.isFile())return null;try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private static void write(File f,byte[] bytes)throws Exception{if(bytes==null){f.delete();return;}try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);}}
    private Button button(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);real=args!=null&&"true".equals(args.getString("real"));start();}
    private void realInspection(){Bundle report=new Bundle();try{
        getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("autoInspect",true).commit();
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long end=System.currentTimeMillis()+150000;while(field("inspector")!=null&&System.currentTimeMillis()<end)Thread.sleep(1000);waitForIdleSync();
        check(field("inspector")==null,"real startup inspection finishes within bounded budget");
        String summary=(String)field("inspectionSummary");log.append("SUMMARY: ").append(summary).append('\n');
        check(!summary.contains("列表更新 0")&&!summary.equals("尚未巡检"),"at least one live public feed refreshed on AM9 PRO network");
        int reachable=0;for(SourceInspector.Health h:((HashMap<String,SourceInspector.Health>)field("channelHealth")).values())if(h.ok)reachable++;
        check(reachable>0,"real media samples reachable on device network: "+reachable);
        log.append("CHANNELS: ").append(((ArrayList)field("channels")).size()).append('\n');
        report.putString("stream",log.toString());
    }catch(Exception e){report.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
    finally{if(activity!=null){call("cancelInspection",new Class<?>[]{});final Activity close=activity;runOnMainSync(new Runnable(){public void run(){close.finish();}});}}
    finish(report.getString("stream","").contains("ERROR")?1:-1,report);}
    @Override public void onStart(){if(real){realInspection();return;}Bundle result=new Bundle();Context ctx=getTargetContext();SharedPreferences prefs=ctx.getSharedPreferences("settings",0);Map<String,?> saved=new HashMap<String,Object>(prefs.getAll());File playlist=new File(ctx.getFilesDir(),"playlist.m3u"),candidates=new File(ctx.getFilesDir(),"inspection-candidates.m3u");byte[] oldList=null,oldCandidates=null;boolean backedUp=false;ServerSocket server=null;ExecutorService threads=Executors.newCachedThreadPool();
        try{
            oldList=read(playlist);oldCandidates=read(candidates);backedUp=true;
            final AtomicInteger feedHits=new AtomicInteger();final AtomicBoolean outage=new AtomicBoolean(),stall=new AtomicBoolean();final ServerSocket fixture=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server=fixture;final ExecutorService pool=threads;final String base="http://127.0.0.1:"+fixture.getLocalPort()+"/";
            new Thread(new Runnable(){public void run(){while(!fixture.isClosed())try{final Socket socket=fixture.accept();pool.execute(new Runnable(){public void run(){try(Socket s=socket){s.setSoTimeout(2000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));String first=in.readLine();if(first==null)return;String file=first.split(" ")[1].substring(1),line;while((line=in.readLine())!=null&&!line.isEmpty()){}byte[] data;String mime="video/mp2t";if(file.equals("feed")){feedHits.incrementAndGet();if(stall.get())Thread.sleep(3500);if(outage.get()){s.getOutputStream().write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}data=("#EXTM3U\n#EXTINF:-1,CCTV5HD\n"+base+"index.m3u8\n").getBytes("UTF-8");mime="text/plain";}else if(file.matches("index.m3u8|seg[0-9]+.ts")){if(file.endsWith(".m3u8"))mime="application/vnd.apple.mpegurl";try(InputStream asset=getContext().getAssets().open(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=asset.read(b))!=-1)bytes.write(b,0,n);data=bytes.toByteArray();}}else{s.getOutputStream().write("HTTP/1.1 404 Missing\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+mime+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);}catch(Exception ignored){}}});}catch(Exception e){break;}}}).start();
            write(playlist,("#EXTM3U\n#EXTINF:-1,央视五套·体育\n"+base+"dead\n#EXTINF:-1,正常测试片\n"+base+"index.m3u8\n").getBytes("UTF-8"));
            prefs.edit().clear().putString("source",base+"feed").putBoolean("autoInspect",true).putInt("mode",1).commit();
            activity=startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitDone();
            check(feedHits.get()>0,"app startup automatically fetches current custom feed");
            ArrayList<Playlist.Channel> channels=(ArrayList<Playlist.Channel>)field("channels");
            check(channels.size()==2&&channels.get(0).name.equals("央视五套·体育")&&channels.get(0).url.equals(base+"index.m3u8"),"startup replaces dead route without renaming or reordering channel (rows="+channels.size()+", names="+field("names")+", summary="+field("inspectionSummary")+")");
            check(((ArrayList<String>)field("names")).get(0).contains("倍"),"channel row displays worst segment delivery margin and decoded frames");
            check(new String(read(playlist),"UTF-8").contains("index.m3u8")&&prefs.contains("inspectionHealth"),"updated list and inspection health persist locally");
            call("play",new Class<?>[]{int.class,boolean.class},1,false);call("showPanel",new Class<?>[]{boolean.class},false);long deadline=System.currentTimeMillis()+20000;
            boolean played=false;while(System.currentTimeMillis()<deadline){org.videolan.libvlc.MediaPlayer active=(org.videolan.libvlc.MediaPlayer)field("player");if(active!=null&&(Boolean)field("seenVideo")&&active.getTime()>500){played=true;break;}Thread.sleep(100);}final Object playing=field("player");check(played,"normal playback available alongside inspection: "+field("state"));
            // This click uses the same visible manual button a remote user selects.
            call("showPanel",new Class<?>[]{boolean.class},true);final Button manual=button(activity.getWindow().getDecorView(),"巡检源");check(manual!=null,"manual inspection entry exists in channel panel");
            final int before=feedHits.get();outage.set(true);runOnMainSync(new Runnable(){public void run(){manual.performClick();}});waitDone();
            check(feedHits.get()==before,"manual entry skips feed and stations with records younger than 12 hours");
            check(channels.size()==2&&field("player")==playing&&(Integer)field("index")==1&&((View)field("panel")).getVisibility()==View.VISIBLE,"manual refresh preserves active player, index and panel navigation (rows="+channels.size()+", samePlayer="+(field("player")==playing)+", index="+field("index")+", panel="+((View)field("panel")).getVisibility()+", state="+field("state")+")");
            check(((TextView)field("inspectionStatus")).getText().toString().contains("12小时"),"UI explains recent records were skipped");
            prefs.edit().remove("inspectionCheckpoints:v2:"+base+"feed").remove("inspectionFeedAt:"+base+"feed").commit();stall.set(true);runOnMainSync(new Runnable(){public void run(){manual.performClick();}});Thread.sleep(100);long cancelAt=System.currentTimeMillis();call("cancelInspection",new Class<?>[]{});check(System.currentTimeMillis()-cancelAt<1000&&field("inspector")==null,"cancelling inspection remains responsive");Thread.sleep(3000);check(((TextView)field("inspectionStatus")).getText().toString().contains("已停止")&&channels.size()==2,"cancelled completion cannot overwrite current list or status");
            final Activity paused=activity;runOnMainSync(new Runnable(){public void run(){callActivityOnPause(paused);}});int backgroundHits=feedHits.get();call("startInspection",new Class<?>[]{boolean.class},true);Thread.sleep(700);check(field("inspector")==null&&feedHits.get()==backgroundHits,"paused app cannot start automatic or manual inspection");
            prefs.edit().putBoolean("autoInspect",false).commit();runOnMainSync(new Runnable(){public void run(){callActivityOnResume(paused);}});prefs.edit().putBoolean("autoInspect",true).commit();
            outage.set(false);stall.set(false);final int launches=feedHits.get();final Activity first=activity;runOnMainSync(new Runnable(){public void run(){first.finish();}});Thread.sleep(200);activity=startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitDone();check(feedHits.get()>launches,"next app startup starts a fresh inspection again");
            result.putString("stream",log.toString());
        }catch(Exception e){result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
        finally{
            if(activity!=null){call("cancelInspection",new Class<?>[]{});final Activity close=activity;runOnMainSync(new Runnable(){public void run(){close.finish();}});}
            if(server!=null)try{server.close();}catch(Exception ignored){}threads.shutdownNow();
            if(backedUp)try{write(playlist,oldList);write(candidates,oldCandidates);}catch(Exception e){result.putString("stream",result.getString("stream","")+"ERROR restoring playlist: "+e.getClass().getSimpleName());}
            SharedPreferences.Editor restore=prefs.edit().clear();for(Map.Entry<String,?> e:saved.entrySet()){Object v=e.getValue();if(v instanceof String)restore.putString(e.getKey(),(String)v);else if(v instanceof Boolean)restore.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)restore.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)restore.putLong(e.getKey(),(Long)v);else if(v instanceof Float)restore.putFloat(e.getKey(),(Float)v);else if(v instanceof Set)restore.putStringSet(e.getKey(),(Set<String>)v);}restore.commit();
        }
        finish(result.getString("stream","").contains("ERROR")?1:-1,result);
    }
}
