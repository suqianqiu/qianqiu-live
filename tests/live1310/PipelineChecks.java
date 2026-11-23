package com.tv.simplelive;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;

/** Deterministic delivery fixtures and a decoder stub. This does not certify real stream quality. */
public final class PipelineChecks {
 static int checks;
 static void check(boolean yes,String label){if(!yes)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 static Playlist.Channel channel(String name,String url){return new Playlist.Channel(name,url,"fixture");}
 static SourceInspector engine(Map<String,SourceInspector.RouteRecord> records,int parallel,int frames){return new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),records,(data,decode,stop)->new SourceInspector.SampleInfo(0,48,frames),parallel);}
 static final class Fixture implements Closeable {
  final ServerSocket socket;final ExecutorService pool=Executors.newCachedThreadPool();final CountDownLatch feedGate=new CountDownLatch(1),backupGate=new CountDownLatch(1),badGate=new CountDownLatch(1);
  final List<String> requests=Collections.synchronizedList(new ArrayList<String>());final AtomicInteger ranges=new AtomicInteger(),badRequests=new AtomicInteger();final String base;
  Fixture()throws Exception{socket=new ServerSocket(0,64,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+socket.getLocalPort();pool.execute(()->{while(!socket.isClosed())try{Socket client=socket.accept();pool.execute(()->serve(client));}catch(IOException e){break;}});}
  void serve(Socket client){try(Socket c=client){c.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));String first=in.readLine();if(first==null)return;String path=first.split(" ")[1],range="",line;while((line=in.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.US).startsWith("range:"))range=line.substring(6).trim();requests.add(path);
   if(path.startsWith("/feed"))feedGate.await(8,TimeUnit.SECONDS);
   if(path.startsWith("/backup"))backupGate.await(8,TimeUnit.SECONDS);
   if(path.startsWith("/slowbad"))badGate.await(8,TimeUnit.SECONDS);
   if(path.startsWith("/bad")||path.startsWith("/slowbad")){badRequests.incrementAndGet();c.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));return;}
   byte[] bytes;String type="video/mp2t";
   if(path.startsWith("/feed")){bytes=("#EXTM3U\n#EXTINF:-1,Late\n"+base+"/late/index.m3u8\n").getBytes("UTF-8");type="application/vnd.apple.mpegurl";}
   else if(path.endsWith(".m3u8")){StringBuilder text=new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:6\n");for(int i=0;i<8;i++)text.append("#EXTINF:6,\nseg"+i+".ts\n");bytes=text.toString().getBytes("UTF-8");type="application/vnd.apple.mpegurl";}
   else if(path.startsWith("/html")){bytes=new byte[4096];Arrays.fill(bytes,(byte)' ');System.arraycopy("<html>not video</html>".getBytes("UTF-8"),0,bytes,0,22);}
   else{bytes=new byte[128*1024];Arrays.fill(bytes,(byte)0x47);}
   int total=bytes.length;boolean partial=!range.isEmpty();if(partial){if(!range.equals("bytes=0-16383"))throw new AssertionError(range);ranges.incrementAndGet();bytes=Arrays.copyOf(bytes,Math.min(16384,bytes.length));}
   OutputStream out=c.getOutputStream();out.write(("HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+(partial?"\r\nContent-Range: bytes 0-"+(bytes.length-1)+"/"+total:"")+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));out.write(bytes);
  }catch(Exception ignored){}}
  public void close(){feedGate.countDown();backupGate.countDown();badGate.countDown();try{socket.close();}catch(IOException ignored){}pool.shutdownNow();}
 }
 static SourceInspector.Listener silent(){return new SourceInspector.Listener(){public void progress(int d,int t,int u){}};}
 public static void main(String[] args)throws Exception{
  ExecutorService run=Executors.newSingleThreadExecutor();
  try{
   try(Fixture f=new Fixture()){
    SourceInspector inspector=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),1,30);CountDownLatch quick=new CountDownLatch(1),qualified=new CountDownLatch(1);AtomicReference<SourceInspector.Entry> first=new AtomicReference<>();long started=System.nanoTime();
    Future<SourceInspector.Result> result=run.submit(()->inspector.inspect(Arrays.asList(channel("Known",f.base+"/known/index.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[]{f.base+"/feed.m3u8"},15000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void quick(SourceInspector.Entry e){first.set(e);quick.countDown();}public void updated(SourceInspector.Entry e){if(e.health.ok)qualified.countDown();}}));
    check(quick.await(2,TimeUnit.SECONDS),"known route becomes visible while remote feed is blocked");long latency=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);check(f.feedGate.getCount()==1&&!result.isDone(),"quick result does not wait for remote list completion");check(first.get().health.reachable&&!first.get().health.ok&&first.get().health.samples==0&&first.get().health.frames==0,"quick reachability never masquerades as qualified quality");
    check(qualified.await(2,TimeUnit.SECONDS),"known route can complete strict sampling before slow feed returns");check(f.ranges.get()>0,"quick probe requests a bounded 16 KiB prefix");f.feedGate.countDown();check(result.get(10,TimeUnit.SECONDS).usable==1,"full strict result survives asynchronous refresh");System.out.println("MEASURED fixture first quick result="+latency+"ms; loopback only");
   }
   try(Fixture f=new Fixture()){
    SourceInspector inspector=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),1,30);CountDownLatch bQualified=new CountDownLatch(1);AtomicBoolean earlyBackup=new AtomicBoolean();AtomicInteger qualifiedCount=new AtomicInteger();
    Future<SourceInspector.Result> future=run.submit(()->inspector.inspect(Arrays.asList(channel("A",f.base+"/a/index.m3u8"),channel("B",f.base+"/b/index.m3u8")),Arrays.asList(channel("A",f.base+"/backup/index.m3u8")),new String[0],15000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void stage(String phase,int d,int t,int u){if(phase.equals("频道验证"))qualifiedCount.set(u);}public void updated(SourceInspector.Entry e){if(e.channel.name.equals("B")&&e.health.ok){synchronized(f.requests){for(String path:f.requests)if(path.startsWith("/backup"))earlyBackup.set(true);}bQualified.countDown();}}}));
    check(bQualified.await(3,TimeUnit.SECONDS),"another channel gets a qualified route before first station backup comparison");check(!earlyBackup.get(),"first-channel backup cannot precede coverage of later channel");f.backupGate.countDown();check(future.get(10,TimeUnit.SECONDS).usable==2,"backup pass preserves both qualified channels");check(qualifiedCount.get()==2,"single-route station is counted qualified only after verification result returns");
   }
   try(Fixture f=new Fixture()){
    SourceInspector inspector=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),1,30);CountDownLatch bQuick=new CountDownLatch(1);
    Future<SourceInspector.Result> future=run.submit(()->inspector.inspect(Arrays.asList(channel("A",f.base+"/slowbad/1.m3u8"),channel("B",f.base+"/b/index.m3u8")),Arrays.asList(channel("A",f.base+"/bad/2.m3u8"),channel("A",f.base+"/bad/3.m3u8")),new String[0],15000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void quick(SourceInspector.Entry e){if(e.channel.name.equals("B")&&e.health.reachable)bQuick.countDown();}}));
    check(bQuick.await(2,TimeUnit.SECONDS),"slow failing station does not prevent another channel's quick result");check(f.badGate.getCount()==1,"quick result arrives while failing station still waits");f.badGate.countDown();check(future.get(10,TimeUnit.SECONDS).entries.size()==2,"failed station remains in playlist");
   }
   try(Fixture f=new Fixture()){
    Map<String,SourceInspector.RouteRecord> records=new ConcurrentHashMap<>();Playlist.Channel c=channel("Bad",f.base+"/bad/index.m3u8");SourceInspector.Result bad=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),2,30).inspect(Arrays.asList(c),Collections.<Playlist.Channel>emptyList(),new String[0],10000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void sampled(String id,SourceInspector.RouteRecord r){records.put(id,r);}});
    int before=f.badRequests.get();check(!bad.entries.get(0).health.ok&&before==1,"failed quick probe skips repeated deep downloads");engine(records,2,30).inspect(Arrays.asList(c),Collections.<Playlist.Channel>emptyList(),new String[0],10000,silent());check(f.badRequests.get()==before,"fresh failed route cache avoids duplicate network checks");
    for(SourceInspector.RouteRecord record:records.values())record.quickAt=System.currentTimeMillis()-SourceInspector.QUICK_FAIL_MS-1000;engine(records,2,30).inspect(Arrays.asList(c),Collections.<Playlist.Channel>emptyList(),new String[0],10000,silent());check(f.badRequests.get()>before,"expired failed quick cache retries instead of suppressing recovery for 12 hours");
   }
   try(Fixture f=new Fixture()){
    Map<String,SourceInspector.RouteRecord> records=new ConcurrentHashMap<>();Playlist.Channel c=channel("Resume",f.base+"/resume/index.m3u8");SourceInspector cancel=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),2,30);AtomicInteger completed=new AtomicInteger();
    SourceInspector.Result partial=cancel.inspect(Arrays.asList(c),Collections.<Playlist.Channel>emptyList(),new String[0],10000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void quick(SourceInspector.Entry e){cancel.cancel();}public void completed(SourceInspector.Entry e){completed.incrementAndGet();}public void sampled(String id,SourceInspector.RouteRecord r){records.put(id,r);}});
    check(partial.partial&&partial.entries.get(0).health.reachable&&!partial.entries.get(0).health.ok,"cancel after quick probe retains reachability without promoting quality");check(completed.get()==0,"quick-only cancellation never records completed comparison");int prefixes=f.ranges.get();SourceInspector.Result resumed=engine(records,2,30).inspect(Arrays.asList(c),Collections.<Playlist.Channel>emptyList(),new String[0],10000,silent());check(f.ranges.get()==prefixes,"resume reuses quick cache instead of downloading prefix again");check(resumed.usable==1&&resumed.entries.get(0).health.samples>=8&&resumed.entries.get(0).health.frames>=30,"resume still performs strict sample and decode requirements");
   }
   try(Fixture f=new Fixture()){
    SourceInspector.Result html=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),2,30).inspect(Arrays.asList(channel("HTML",f.base+"/html/index.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[0],10000,silent());check(!html.entries.get(0).health.ok,"HTML response with video MIME cannot pass quick media feature check");
    SourceInspector.Result frames=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),2,29).inspect(Arrays.asList(channel("Frames",f.base+"/frames/index.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[0],10000,silent());check(!frames.entries.get(0).health.ok,"29 decoded frames never receive qualified label");
   }
   try(Fixture f=new Fixture()){
    SourceInspector inspector=engine(Collections.<String,SourceInspector.RouteRecord>emptyMap(),2,30);CountDownLatch lateQuick=new CountDownLatch(1);Future<SourceInspector.Result> future=run.submit(()->inspector.inspect(Arrays.asList(channel("Late",f.base+"/bad/original.m3u8")),Collections.<Playlist.Channel>emptyList(),new String[]{f.base+"/feed.m3u8"},15000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void quick(SourceInspector.Entry e){if(e.health.reachable)lateQuick.countDown();}}));
    Thread.sleep(200);f.feedGate.countDown();check(lateQuick.await(3,TimeUnit.SECONDS),"late refreshed route receives a quick result before deep validation");SourceInspector.Result r=future.get(10,TimeUnit.SECONDS);check(r.usable==1&&r.entries.get(0).channel.url.contains("/late/"),"new candidate repairs failed original without dropping channel");check(r.candidates.size()==2&&r.feedsOk==1,"refreshed candidates persist in final cache snapshot");
   }
   try(Fixture f=new Fixture()){
    Playlist.Channel original=channel("Late",f.base+"/bad/cached.m3u8");SourceInspector.Health failed=new SourceInspector.Health();failed.tested=true;failed.testedAt=System.currentTimeMillis();failed.state="无合格源";failed.routes=1;Map<String,SourceInspector.Entry> previous=new HashMap<>();previous.put(SourceInspector.key("Late"),new SourceInspector.Entry(original,failed));AtomicInteger qualifiedCount=new AtomicInteger();
    SourceInspector inspector=new SourceInspector(Collections.<String>emptySet(),previous,Collections.<String,SourceInspector.RouteRecord>emptyMap(),(data,decode,stop)->new SourceInspector.SampleInfo(0,48,30),2);
    Future<SourceInspector.Result> future=run.submit(()->inspector.inspect(Arrays.asList(original),Collections.<Playlist.Channel>emptyList(),new String[]{f.base+"/feed.m3u8"},15000,new SourceInspector.Listener(){public void progress(int d,int t,int u){}public void stage(String phase,int d,int t,int u){if(phase.equals("频道验证"))qualifiedCount.set(u);}}));
    Thread.sleep(100);f.feedGate.countDown();SourceInspector.Result result=future.get(10,TimeUnit.SECONDS);check(result.usable==1&&result.entries.get(0).channel.url.contains("/late/"),"new candidate invalidates cached failed channel decision immediately");check(qualifiedCount.get()==1,"recovered cached failure increments qualified count without double-counting verified channel");
   }
   try(Fixture f=new Fixture()){
    Playlist.Channel recovered=channel("Recovered",f.base+"/recovered/index.m3u8");SourceInspector.Health oldFailure=new SourceInspector.Health();oldFailure.tested=true;oldFailure.testedAt=System.currentTimeMillis()-1000;oldFailure.routes=1;Map<String,SourceInspector.Entry> previous=new HashMap<>();previous.put(SourceInspector.key("Recovered"),new SourceInspector.Entry(channel("Recovered",f.base+"/bad/old.m3u8"),oldFailure));
    SourceInspector.RouteRecord verified=new SourceInspector.RouteRecord();verified.startedAt=verified.completedAt=System.currentTimeMillis();verified.round=2;verified.frames=30;for(int i=0;i<8;i++)verified.samples.add(new SourceInspector.Sample(6,.1,4096));Map<String,SourceInspector.RouteRecord> records=new HashMap<>();records.put(SourceInspector.key("Recovered")+"\n"+recovered.url,verified);
    SourceInspector inspector=new SourceInspector(Collections.<String>emptySet(),previous,records,(data,decode,stop)->new SourceInspector.SampleInfo(0,48,30),2);SourceInspector.Result result=inspector.inspect(Arrays.asList(recovered),Collections.<Playlist.Channel>emptyList(),new String[0],10000,silent());
    check(result.usable==1&&result.entries.get(0).channel.url.equals(recovered.url),"restored qualified route cannot be replaced by older cached channel failure");check(f.requests.isEmpty(),"restored qualified route reuses strict sample cache without network re-download");
   }
   System.out.println("RESULT PASS checks="+checks+"; deterministic HTTP fixtures and decoder stub; no real TV latency or quality claim");
  }finally{run.shutdownNow();}
 }
}
