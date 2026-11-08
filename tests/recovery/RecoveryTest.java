package com.tv.simplelive;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;
import java.io.*;import java.net.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.videolan.libvlc.MediaPlayer;

/** Real HTTP input freezes without EOF/error; the production watchdog must reconnect it. */
public final class RecoveryTest extends Instrumentation {
 MainActivity activity;volatile boolean stop,fail;AtomicInteger connections=new AtomicInteger();StringBuilder log=new StringBuilder();
 Object field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(activity);}
 void set(String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(activity,v);}
 void call(final String n,final Class<?>[] types,final Object...args){runOnMainSync(new Runnable(){public void run(){try{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}});}
 void check(boolean ok,String s)throws Exception{if(!ok)throw new Exception(s);log.append("PASS: "+s+"\n");}
 boolean video()throws Exception{MediaPlayer p=(MediaPlayer)field("player");return p!=null&&(Boolean)field("seenVideo")&&p.getTime()>500;}
 void waitVideo()throws Exception{long end=SystemClock.elapsedRealtime()+20000;while(!video()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);check(video(),"production player outputs fixture video");}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();ServerSocket server=null;ExecutorService pool=Executors.newCachedThreadPool();SharedPreferences prefs=getTargetContext().getSharedPreferences("settings",0);Map<String,?> saved=prefs.getAll();
  try{
   final byte[][] parts=new byte[9][];for(int i=0;i<9;i++){ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open(String.format(java.util.Locale.US,"seg%03d.ts",i))){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);}parts[i]=b.toByteArray();}
   final ServerSocket fixture=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server=fixture;final ExecutorService workers=pool;
   new Thread(new Runnable(){public void run(){while(!stop)try{final Socket accepted=fixture.accept();workers.execute(new Runnable(){public void run(){try(Socket s=accepted){s.setSoTimeout(3000);BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream()));String first=in.readLine(),line;if(first==null)return;while((line=in.readLine())!=null&&!line.isEmpty()){}OutputStream out=s.getOutputStream();if(fail){out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}
    int serial=connections.incrementAndGet();out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));int count=serial==1?2:9;for(int n=0;n<count;n++){out.write(parts[n]);out.flush();}
    // First connection stalls forever with no EOF; reconnects receive a fresh healthy stream.
    while(!stop)Thread.sleep(100);
   }catch(Exception ignored){}}});}catch(Exception e){break;}}}).start();
   prefs.edit().putBoolean("autoInspect",false).putInt("mode",1).commit();activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   final String url="http://127.0.0.1:"+fixture.getLocalPort()+"/live.ts";
   runOnMainSync(new Runnable(){public void run(){try{ArrayList<Playlist.Channel> channels=(ArrayList<Playlist.Channel>)field("channels");channels.clear();channels.add(new Playlist.Channel("freeze test",url,""));channels.add(new Playlist.Channel("manual test",url,""));}catch(Exception e){throw new RuntimeException(e);}}});call("updateList",new Class<?>[]{});call("play",new Class<?>[]{int.class,boolean.class},0,false);call("showPanel",new Class<?>[]{boolean.class},false);waitVideo();
   final int original=(Integer)field("playGeneration");long end=SystemClock.elapsedRealtime()+45000;while((connections.get()<2||!video())&&SystemClock.elapsedRealtime()<end)Thread.sleep(200);
   check(connections.get()>=2&&video()&&(Integer)field("playGeneration")>original,"frozen HTTP stream automatically reconnects and resumes without remote input");
   check((Integer)field("index")==0&&((View)field("panel")).getVisibility()!=View.VISIBLE,"automatic recovery preserves channel and fullscreen");
   check(((PlaybackRecovery)field("recovery")).attempts()==1,"successful recovery retains retry count until stable window");
   call("scheduleReconnect",new Class<?>[]{String.class},"manual cancellation test");call("play",new Class<?>[]{int.class,boolean.class},1,false);waitVideo();int generation=(Integer)field("playGeneration");Thread.sleep(3500);
   check((Integer)field("index")==1&&(Integer)field("playGeneration")==generation&&((PlaybackRecovery)field("recovery")).attempts()==0,"manual channel switch cancels old delayed reconnect and resets budget");
   fail=true;call("scheduleReconnect",new Class<?>[]{String.class},"persistent failure test");end=SystemClock.elapsedRealtime()+35000;while(((View)field("playbackError")).getVisibility()!=View.VISIBLE&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
   check(((PlaybackRecovery)field("recovery")).attempts()==3&&((View)field("playbackError")).getVisibility()==View.VISIBLE&&field("player")==null,"persistent outage stops after three attempts with visible failure instead of infinite retry");
   Thread.sleep(2500);check(field("reconnect")==null,"exhausted recovery leaves no delayed retry");
   fail=false;call("play",new Class<?>[]{int.class,boolean.class},0,false);waitVideo();call("scheduleReconnect",new Class<?>[]{String.class},"background cancellation test");runOnMainSync(new Runnable(){public void run(){activity.finish();}});Thread.sleep(2200);check(field("reconnect")==null&&(Boolean)field("destroyed"),"leaving app cancels recovery");
   result.putString("stream",log.toString());
  }catch(Throwable e){result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
  finally{stop=true;if(server!=null)try{server.close();}catch(Exception ignored){}pool.shutdownNow();if(activity!=null)runOnMainSync(new Runnable(){public void run(){activity.finish();}});SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> e:saved.entrySet()){Object v=e.getValue();String k=e.getKey();if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Integer)edit.putInt(k,(Integer)v);else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);else if(v instanceof Long)edit.putLong(k,(Long)v);else if(v instanceof Float)edit.putFloat(k,(Float)v);else if(v instanceof Set)edit.putStringSet(k,(Set<String>)v);}edit.commit();}
  finish(result.getString("stream","").contains("ERROR:")?1:-1,result);
 }
}
