package com.tv.simplelive;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class EngineProgressTest {
 static int checks;
 static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 static Playlist.Channel channel(String name,String url){return new Playlist.Channel(name,url,"fixture");}
 static SourceInspector engine(Map<String,SourceInspector.RouteRecord> records){return new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),records,new SourceInspector.Decoder(){public SourceInspector.SampleInfo analyze(byte[] data,boolean decode,SourceInspector.Stop stop){return new SourceInspector.SampleInfo(0,48,30);}},2);}
 static final class Fixture implements Closeable {
  final ServerSocket socket;final ExecutorService pool=Executors.newCachedThreadPool();final CountDownLatch slow=new CountDownLatch(1);final AtomicInteger fastSegments=new AtomicInteger(),badRequests=new AtomicInteger();final String base;
  Fixture()throws Exception{socket=new ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+socket.getLocalPort();pool.execute(new Runnable(){public void run(){while(!socket.isClosed())try{final Socket client=socket.accept();pool.execute(new Runnable(){public void run(){serve(client);}});}catch(IOException e){break;}}});}
  void serve(Socket client){try(Socket c=client){c.setSoTimeout(3000);BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String first=r.readLine();if(first==null)return;String path=first.split(" ")[1],line;while((line=r.readLine())!=null&&!line.isEmpty()){}boolean bad=path.startsWith("/bad")||path.startsWith("/slowbad");if(path.startsWith("/slow"))slow.await(5,TimeUnit.SECONDS);byte[] data;
   if(bad){badRequests.incrementAndGet();c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}
   if(path.endsWith("m3u8")){StringBuilder text=new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:6\n");for(int i=0;i<8;i++)text.append("#EXTINF:6,\nseg"+i+".ts\n");text.append("#EXT-X-ENDLIST\n");data=text.toString().getBytes("UTF-8");}
   else{if(path.startsWith("/fast/"))fastSegments.incrementAndGet();data=new byte[4096];Arrays.fill(data,(byte)0x47);}
   OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(data);
  }catch(Exception ignored){}}
  public void close(){try{socket.close();}catch(IOException ignored){}slow.countDown();pool.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{
  final ExecutorService jobs=Executors.newSingleThreadExecutor();
  try(final Fixture fixture=new Fixture()){
   final Playlist.Channel fast=channel("即时频道",fixture.base+"/fast/index.m3u8"),bad=channel("即时频道",fixture.base+"/slowbad/index.m3u8");
   final SourceInspector first=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap());final CountDownLatch updated=new CountDownLatch(1);final AtomicInteger completed=new AtomicInteger();final List<SourceInspector.Entry> updates=Collections.synchronizedList(new ArrayList<SourceInspector.Entry>());
   Future<SourceInspector.Result> future=jobs.submit(new Callable<SourceInspector.Result>(){public SourceInspector.Result call()throws Exception{return first.inspect(Arrays.asList(fast),Arrays.asList(bad),new String[0],10000,new SourceInspector.Listener(){public void progress(int a,int b,int c){}public void updated(SourceInspector.Entry e){updates.add(e);updated.countDown();}public void completed(SourceInspector.Entry e){completed.incrementAndGet();}});}});
   check(updated.await(3,TimeUnit.SECONDS),"first route emits quality before other routes complete");check(!future.isDone()&&completed.get()==0,"one channel need not finish all candidates before display");check(updates.get(0).health.ok&&updates.get(0).health.state.contains("1/2"),"interim quality includes measured route count");fixture.slow.countDown();SourceInspector.Result result=future.get(10,TimeUnit.SECONDS);
   check(updates.size()==2,"each measured route publishes an incremental result");check(updates.get(1).health.ok&&updates.get(1).channel.url.equals(fast.url),"later bad route cannot erase an already qualified route");check(result.entries.get(0).health.ok&&completed.get()==1&&!result.partial,"completed result preserves the best tested route");
   final Map<String,SourceInspector.RouteRecord> saved=new ConcurrentHashMap<String,SourceInspector.RouteRecord>();final SourceInspector cancel=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap());final AtomicInteger finalCallbacks=new AtomicInteger();
   SourceInspector.Result partial=cancel.inspect(Arrays.asList(fast),Arrays.asList(bad),new String[0],10000,new SourceInspector.Listener(){public void progress(int a,int b,int c){}public void sampled(String id,SourceInspector.RouteRecord r){saved.put(id,r);}public void updated(SourceInspector.Entry e){cancel.cancel();}public void completed(SourceInspector.Entry e){finalCallbacks.incrementAndGet();}});
   check(partial.partial&&partial.entries.get(0).health.ok,"cancelled inspection keeps already measured quality");check(partial.entries.get(0).channel.url.equals(fast.url),"partial result retains qualified playback URL");check(finalCallbacks.get()==0,"unfinished comparison never becomes a completed checkpoint");
   int bytesBefore=fixture.fastSegments.get(),badBefore=fixture.badRequests.get();SourceInspector.Result resumed=engine(saved).inspect(Arrays.asList(fast),Arrays.asList(bad),new String[0],10000,new SourceInspector.Listener(){public void progress(int a,int b,int c){}});
   check(fixture.fastSegments.get()==bytesBefore,"resume reuses complete samples without downloading them again");check(fixture.badRequests.get()>badBefore&&!resumed.partial,"resume still inspects remaining routes after partial quality was shown");
   final AtomicInteger failedUpdates=new AtomicInteger();SourceInspector.Result failed=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap()).inspect(Arrays.asList(channel("坏源频道",fixture.base+"/bad1/index.m3u8")),Arrays.asList(channel("坏源频道",fixture.base+"/bad2/index.m3u8")),new String[0],10000,new SourceInspector.Listener(){public void progress(int a,int b,int c){}public void updated(SourceInspector.Entry e){failedUpdates.incrementAndGet();check(!e.health.ok&&e.health.testedAt>0,"failed route also provides an immediate measured status");}});
   check(failedUpdates.get()==2&&!failed.entries.get(0).health.ok,"all failed candidates remain failed after incremental updates");
   System.out.println("RESULT PASS checks="+checks+"; deterministic HTTP fixtures and decoder stub; no real stream quality claim");
  }finally{jobs.shutdownNow();}
 }
}
