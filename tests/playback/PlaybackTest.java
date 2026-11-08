package com.tv.simplelive;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.graphics.*;
import java.io.*;import java.net.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
import org.videolan.libvlc.MediaPlayer;
public final class PlaybackTest extends Instrumentation {
 Activity a;String origin;String remote="";StringBuilder log=new StringBuilder();
 Object field(String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
 void set(String n,Object v)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
 void call(final String n,final Class<?>[] types,final Object...args){runOnMainSync(new Runnable(){public void run(){try{Method m=a.getClass().getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(a,args);}catch(Exception e){throw new RuntimeException(e);}}});}
 void check(boolean b,String s)throws Exception{if(!b)throw new Exception(s);log.append("PASS: "+s+"\n");}
 void launch()throws Exception{
  getTargetContext().getSharedPreferences("settings",0).edit().putInt("mode",0).commit();
  getTargetContext().getSharedPreferences("settings",0).edit().putBoolean("autoInspect",false).commit();
  a=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
  runOnMainSync(new Runnable(){public void run(){try{ArrayList<Playlist.Channel> channels=(ArrayList<Playlist.Channel>)field("channels");channels.clear();channels.add(new Playlist.Channel("test 1",origin+"index.m3u8",""));channels.add(new Playlist.Channel("test 2",origin+"index.m3u8",""));}catch(Exception e){throw new RuntimeException(e);}}});call("updateList",new Class<?>[]{});play(0);
 }
 void play(final int mode)throws Exception{runOnMainSync(new Runnable(){public void run(){try{set("mode",mode);}catch(Exception e){throw new RuntimeException(e);}}});call("play",new Class<?>[]{int.class,boolean.class},((Integer)field("index")+1)%2,false);call("showPanel",new Class<?>[]{boolean.class},false);}
 void video(String name)throws Exception{
  long end=System.currentTimeMillis()+35000,at=-1;while(System.currentTimeMillis()<end){MediaPlayer p=(MediaPlayer)field("player");if(p!=null&&(Boolean)field("seenVideo")&&p.getTime()>500&&p.isPlaying()){at=p.getTime();break;}Thread.sleep(100);}
  check(at>=500,name+" video output and clock");Thread.sleep(1600);MediaPlayer p=(MediaPlayer)field("player");check(p!=null&&p.getTime()>at+500,name+" clock advances");
  call("showPanel",new Class<?>[]{boolean.class},false);Thread.sleep(300);
  Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,name+" screenshot");int bright=0;HashSet<Integer> colors=new HashSet<Integer>();for(int y=b.getHeight()/3;y<b.getHeight()*2/3;y+=13)for(int x=b.getWidth()/3;x<b.getWidth()*2/3;x+=13){int c=b.getPixel(x,y);if(Color.red(c)+Color.green(c)+Color.blue(c)>90)bright++;colors.add(c&0xFFF0F0F0);}check(bright>80&&colors.size()>12,name+" nonblack varying video pixels ("+bright+"/"+colors.size()+")");try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"playback-"+name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
 }
 void close()throws Exception{final Activity old=a;runOnMainSync(new Runnable(){public void run(){old.finish();}});long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end&&(!(Boolean)field("destroyed")||(Integer)field("closingPlayers")!=0||field("vlc")!=null))Thread.sleep(100);check((Boolean)field("destroyed")&&(Integer)field("closingPlayers")==0&&field("vlc")==null,"finish drains players then core");Thread.sleep(1300);a=null;}
 @Override public void onCreate(Bundle b){super.onCreate(b);remote=b==null?"":b.getString("url","");start();}
 @Override public void onStart(){Bundle result=new Bundle();ServerSocket server=null;ExecutorService pool=Executors.newCachedThreadPool();Map<String,Map<String,?>> saved=new HashMap<String,Map<String,?>>();
  for(String name:new String[]{"settings"})saved.put(name,getTargetContext().getSharedPreferences(name,0).getAll());
  try{
   final ServerSocket fixture=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));server=fixture;final ExecutorService threads=pool;origin="http://127.0.0.1:"+fixture.getLocalPort()+"/";
   new Thread(new Runnable(){public void run(){while(!fixture.isClosed())try{final Socket s=fixture.accept();threads.execute(new Runnable(){public void run(){try(Socket socket=s){socket.setSoTimeout(3000);BufferedReader r=new BufferedReader(new InputStreamReader(socket.getInputStream()));String first=r.readLine(),line;if(first==null)return;String file=first.split(" ")[1].substring(1);while((line=r.readLine())!=null&&!line.isEmpty()){}if(!file.matches("index.m3u8|seg[0-9]+.ts"))return;ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getContext().getAssets().open(file)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)bytes.write(buf,0,n);}byte[] data=bytes.toByteArray();OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+(file.endsWith("m3u8")?"application/vnd.apple.mpegurl":"video/mp2t")+"\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);}catch(Exception ignored){}}});}catch(Exception e){break;}}}).start();
   if(!remote.isEmpty()){origin=remote.substring(0,remote.lastIndexOf("/")+1);launch();runOnMainSync(new Runnable(){public void run(){try{ArrayList<Playlist.Channel> channels=(ArrayList<Playlist.Channel>)field("channels");channels.clear();channels.add(new Playlist.Channel("network sample",remote,""));}catch(Exception e){throw new RuntimeException(e);}}});call("updateList",new Class<?>[]{});call("play",new Class<?>[]{int.class,boolean.class},0,false);call("showPanel",new Class<?>[]{boolean.class},false);video("network");Thread.sleep(5000);video("network-sustained");close();}else{
   launch();video("auto");play(1);video("hardware");play(2);video("software");
   for(int n=0;n<5;n++)play(0);video("rapid-switch");play(0);video("next-channel");
   close();launch();video("reopen");close();}result.putString("stream",log.toString());
  }catch(Throwable e){result.putString("stream",log+"ERROR: "+android.util.Log.getStackTraceString(e));}
  finally{if(a!=null){runOnMainSync(new Runnable(){public void run(){a.finish();}});try{Thread.sleep(2000);}catch(Exception ignored){}}if(server!=null)try{server.close();}catch(Exception ignored){}pool.shutdownNow();for(String name:saved.keySet()){SharedPreferences.Editor ed=getTargetContext().getSharedPreferences(name,0).edit().clear();for(Map.Entry<String,?> e:saved.get(name).entrySet()){Object v=e.getValue();String k=e.getKey();if(v instanceof String)ed.putString(k,(String)v);else if(v instanceof Integer)ed.putInt(k,(Integer)v);else if(v instanceof Long)ed.putLong(k,(Long)v);else if(v instanceof Boolean)ed.putBoolean(k,(Boolean)v);else if(v instanceof Float)ed.putFloat(k,(Float)v);else if(v instanceof Set)ed.putStringSet(k,(Set<String>)v);}ed.commit();}}
  finish(result.getString("stream","").contains("ERROR:")?1:-1,result);
 }
}
