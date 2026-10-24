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

/** Device regression: real failed/working streams and remote navigation, with isolated channels. */
public final class NavigationTest extends Instrumentation {
    private MainActivity activity;
    private boolean realRoutes;
    private final StringBuilder log = new StringBuilder();
    private Object field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity); }
    private void set(String name,Object value) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value); }
    private void call(final String name, final Class<?>[] types, final Object... args) {
        runOnMainSync(new Runnable(){public void run(){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}});
    }
    private void check(boolean yes,String message)throws Exception{if(!yes)throw new Exception("FAIL: "+message);log.append("PASS: ").append(message).append('\n');}
    private boolean panel()throws Exception{return ((View)field("panel")).getVisibility()==View.VISIBLE;}
    private boolean error()throws Exception{return ((View)field("playbackError")).getVisibility()==View.VISIBLE;}
    private int generation()throws Exception{return (Integer)field("playGeneration");}
    private void play(int index,boolean full){call("play",new Class<?>[]{int.class,boolean.class},index,false);if(full)call("showPanel",new Class<?>[]{boolean.class},false);}
    private void waitPlayer()throws Exception{long end=System.currentTimeMillis()+15000;while(field("player")==null&&System.currentTimeMillis()<end)Thread.sleep(50);check(field("player")!=null,"queued channel starts after prior player cleanup");}
    private void waitError()throws Exception{long end=System.currentTimeMillis()+15000;while(!error()&&System.currentTimeMillis()<end)Thread.sleep(100);check(error(),"failed stream displays unavailable message");}
    private void waitVideo()throws Exception{long end=System.currentTimeMillis()+(realRoutes?90000:20000);boolean yes=false;while(System.currentTimeMillis()<end){org.videolan.libvlc.MediaPlayer p=(org.videolan.libvlc.MediaPlayer)field("player");if(p!=null&&(Boolean)field("seenVideo")&&p.getTime()>500){yes=true;break;}Thread.sleep(100);}check(yes,"next channel renders video and advances playback");}
    @Override public void onCreate(Bundle args){super.onCreate(args);realRoutes=args!=null&&"true".equals(args.getString("real"));start();}
    @Override public void onStart(){Bundle result=new Bundle();ServerSocket server=null;ExecutorService pool=Executors.newCachedThreadPool();SharedPreferences prefs=getTargetContext().getSharedPreferences("settings",0);final boolean hadMode=prefs.contains("mode"),hadIndex=prefs.contains("lastIndex");final int oldMode=prefs.getInt("mode",0),oldIndex=prefs.getInt("lastIndex",-1);final boolean hadAuto=prefs.contains("autoInspect"),oldAuto=prefs.getBoolean("autoInspect",true);prefs.edit().putBoolean("autoInspect",false).commit();
        try{
            final ServerSocket fixture=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server=fixture;final ExecutorService threads=pool;
            new Thread(new Runnable(){public void run(){while(!fixture.isClosed())try{final Socket socket=fixture.accept();threads.execute(new Runnable(){public void run(){try(Socket s=socket){s.setSoTimeout(2000);BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream()));String first=input.readLine();if(first==null)return;String file=first.split(" ")[1].substring(1),line;while((line=input.readLine())!=null&&!line.isEmpty()){}if(file.equals("stall")){Thread.sleep(60000);return;}OutputStream out=s.getOutputStream();if(file.equals("fail")){out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}if(!file.matches("index.m3u8|seg[0-9]+.ts"))return;ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}byte[] data=bytes.toByteArray();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);}catch(Exception ignored){}}});}catch(Exception e){break;}}}).start();
            final String origin="http://127.0.0.1:"+fixture.getLocalPort()+"/";
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(new Runnable(){public void run(){try{ArrayList<Playlist.Channel> channels=(ArrayList<Playlist.Channel>)field("channels");channels.clear();channels.add(new Playlist.Channel("故障频道（测试）",origin+"fail",""));channels.add(new Playlist.Channel("正常频道（测试片）",origin+"index.m3u8",""));channels.add(new Playlist.Channel("等待信号（测试）",origin+"stall",""));set("mode",1);}catch(Exception e){throw new RuntimeException(e);}}});call("updateList",new Class<?>[]{});
            if(realRoutes){
                final ArrayList<Playlist.Channel> real=new ArrayList<Playlist.Channel>();
                try(InputStream in=getTargetContext().getAssets().open("public-cn.m3u")){for(Playlist.Channel c:Playlist.parse(Playlist.read(in),""))if(c.name.equals("央视五套·体育")||c.name.equals("央视六套·电影"))real.add(c);}
                check(real.size()==2,"CCTV-5 and CCTV-6 routes loaded from installed bundled playlist");
                runOnMainSync(new Runnable(){public void run(){try{((ArrayList<Playlist.Channel>)field("channels")).addAll(real);}catch(Exception e){throw new RuntimeException(e);}}});call("updateList",new Class<?>[]{});
                for(int target=3;target<5;target++){
                    play(target,true);waitPlayer();Thread.sleep(2500);
                    long inputAt=System.currentTimeMillis();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
                    check(System.currentTimeMillis()-inputAt<1500,"channel-down responds promptly after real "+real.get(target-3).name+" connection");
                    play(1,true);waitVideo();check(!error()&&!panel()&&(Integer)field("index")==1,"healthy channel recovers after leaving real "+real.get(target-3).name+" without ANR");
                }
            }else{
            play(0,true);waitError();check(!panel()&&(Integer)field("index")==0&&field("player")==null,"playback failure keeps fullscreen and current channel index");
            String message=((TextView)field("playbackError")).getText().toString();check(message.contains("该电视台无法播放")&&message.contains("↑")&&message.contains("↓")&&message.contains("Back"),"persistent error explains channel up/down and Back controls");
            Thread.sleep(6500);check(error()&&!panel(),"failure notice remains after normal six-second hint timeout");
            try{android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot!=null){try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"navigation-error-1.0.8.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();}}catch(Exception ignored){}
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);waitVideo();check(!panel()&&!error()&&(Integer)field("index")==1,"Down after failure plays the next channel without visiting list");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);waitError();check((Integer)field("index")==0&&!panel(),"Up after successful playback returns to failed channel in fullscreen");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);check(panel()&&((ListView)field("list")).getSelectedItemPosition()==0,"Back opens list at the failed channel");
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitVideo();check(!panel()&&(Integer)field("index")==1,"list selection and Confirm still start the selected channel");
            play(2,true);final int stale=generation();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);call("failed",new Class<?>[]{int.class},stale);call("noVideo",new Class<?>[]{int.class},stale);waitVideo();check(!panel()&&!error()&&(Integer)field("index")==1,"old channel error and timeout cannot interrupt rapid channel switch");
            play(2,true);waitPlayer();call("noVideo",new Class<?>[]{int.class},generation());check(error()&&!panel()&&(Integer)field("index")==2,"no-video timeout keeps fullscreen and channel navigation");
            runOnMainSync(new Runnable(){public void run(){try{set("mode",0);}catch(Exception e){throw new RuntimeException(e);}}});play(2,true);waitPlayer();int hardGeneration=generation();call("noVideo",new Class<?>[]{int.class},hardGeneration);check((Boolean)field("softwareRetry")&&!panel()&&!error()&&(Integer)field("index")==2,"automatic decoder retries software once without opening list");call("failed",new Class<?>[]{int.class},hardGeneration);waitForIdleSync();check(!error(),"old hard-decoder failure cannot cancel software retry");call("failed",new Class<?>[]{int.class},generation());waitError();check(!panel(),"failed software retry stays fullscreen");
            call("showPanel",new Class<?>[]{boolean.class},true);play(2,false);waitPlayer();call("noVideo",new Class<?>[]{int.class},generation()); // auto first retry
            waitPlayer();call("noVideo",new Class<?>[]{int.class},generation());check(panel()&&error(),"failure preserves an already-open list rather than changing navigation");
            // Wait for ordinary queued teardown before injecting cleanup faults.
            long idleDeadline=System.currentTimeMillis()+15000;while((Integer)field("closingPlayers")!=0&&System.currentTimeMillis()<idleDeadline)Thread.sleep(50);
            check((Integer)field("closingPlayers")==0,"normal cleanup drains before fault injection");
            for(final String stage:new String[]{"listener","stop","detach","release"}){
                check(!PlayerCleanup.run(stage,new Runnable(){public void run(){throw new IllegalStateException("injected");}}),"cleanup contains injected Java exception at "+stage);
            }
            check(!PlayerCleanup.run("linkage",new Runnable(){public void run(){throw new UnsatisfiedLinkError("injected");}}),"cleanup contains JNI linkage error");
            final java.util.concurrent.CountDownLatch stopping=new java.util.concurrent.CountDownLatch(1),allowStop=new java.util.concurrent.CountDownLatch(1);
            final org.videolan.libvlc.LibVLC core=(org.videolan.libvlc.LibVLC)field("vlc");
            final org.videolan.libvlc.MediaPlayer slow=new org.videolan.libvlc.MediaPlayer(core){
                @Override public void stop(){stopping.countDown();try{allowStop.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}super.stop();throw new IllegalStateException("injected stop failure");}
                @Override public org.videolan.libvlc.interfaces.IVLCVout getVLCVout(){throw new IllegalStateException("injected detach failure");}
            };
            runOnMainSync(new Runnable(){public void run(){try{set("player",slow);set("mode",1);}catch(Exception e){throw new RuntimeException(e);}}});
            long before=System.currentTimeMillis();call("unavailable",new Class<?>[]{String.class},"注入停止异常测试");
            check(System.currentTimeMillis()-before<1500&&error(),"failure notice appears without waiting for blocked native stop");
            check(stopping.await(2,TimeUnit.SECONDS),"native stop is executed on cleanup worker");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);if(panel())sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            check(!panel()&&(Integer)field("index")==1,"remote input remains responsive while old stop is blocked; latest channel wins");
            Thread.sleep(5500);final java.util.concurrent.atomic.AtomicBoolean responsive=new java.util.concurrent.atomic.AtomicBoolean();runOnMainSync(new Runnable(){public void run(){responsive.set(true);}});check(responsive.get()&&error(),"UI remains responsive beyond ANR threshold during delayed stop");
            allowStop.countDown();waitVideo();check(!error()&&!panel()&&(Integer)field("index")==1,"stop and detach exceptions do not prevent latest channel playback");
            }
            result.putString("stream",log.toString());
        }catch(Exception e){result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
        finally{if(server!=null)try{server.close();}catch(Exception ignored){}pool.shutdownNow();if(activity!=null)runOnMainSync(new Runnable(){public void run(){activity.finish();}});SharedPreferences.Editor edit=prefs.edit();if(hadMode)edit.putInt("mode",oldMode);else edit.remove("mode");if(hadIndex)edit.putInt("lastIndex",oldIndex);else edit.remove("lastIndex");if(hadAuto)edit.putBoolean("autoInspect",oldAuto);else edit.remove("autoInspect");edit.commit();}
        finish(result.getString("stream","").contains("ERROR:")?1:-1,result);
    }
}
