package com.tv.simplelive;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local HTTP fixtures exercise full transfers, ranking, cancellation, and persisted samples. */
public final class SourceInspectorTest {
    private static int passed;
    private static final AtomicInteger decoderCalls=new AtomicInteger(),rangeHits=new AtomicInteger();
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);System.out.println("PASS: "+message);passed++;}
    private static Playlist.Channel c(String name,String url){return new Playlist.Channel(name,url,"test");}
    private static final SourceInspector.Decoder DECODER=new SourceInspector.Decoder(){public SourceInspector.SampleInfo analyze(byte[] bytes,boolean decode,SourceInspector.Stop stop){decoderCalls.incrementAndGet();return new SourceInspector.SampleInfo(0,10,bytes[bytes.length-1]=='B'?0:30);}};
    private static final SourceInspector.Decoder STREAM_DECODER=new SourceInspector.Decoder(){public SourceInspector.SampleInfo analyze(byte[] bytes,boolean decode,SourceInspector.Stop stop){return new SourceInspector.SampleInfo(0,bytes.length/196648.0,decode?30:0);}};
    private static SourceInspector engine(){return new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),Collections.<String,SourceInspector.RouteRecord>emptyMap(),DECODER);}
    private static SourceInspector.Result scan(SourceInspector engine,List<Playlist.Channel> old,List<Playlist.Channel> cache,String[] feeds,long budget,SourceInspector.Listener listener)throws Exception{return engine.inspect(old,cache,feeds,budget,listener);}
    private static final List<Playlist.Channel> EMPTY=Collections.emptyList();
    public static void main(String[] args)throws Exception{
        final ConcurrentHashMap<String,AtomicInteger> hits=new ConcurrentHashMap<String,AtomicInteger>();
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final ExecutorService threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        final String base="http://127.0.0.1:"+server.getAddress().getPort();
        final byte[] media=new byte[196648];for(int i=0;i<media.length;i+=188)media[i]=0x47;media[media.length-1]='G';
        server.createContext("/",new HttpHandler(){public void handle(HttpExchange x)throws IOException{
            try{
                String path=x.getRequestURI().getPath();hits.putIfAbsent(path,new AtomicInteger());hits.get(path).incrementAndGet();if(x.getRequestHeaders().getFirst("Range")!=null)rangeHits.incrementAndGet();
                byte[] data;
                if(path.equals("/dead")){x.sendResponseHeaders(404,-1);return;}
                if(path.equals("/stream-redirect.ts")){x.getResponseHeaders().add("Location","/continuous.ts");x.sendResponseHeaders(302,-1);return;}
                if(path.equals("/continuous.ts")){x.sendResponseHeaders(200,media.length*180L);try{for(int i=0;i<180;i++)x.getResponseBody().write(media);}catch(IOException ignored){}return;}
                if(path.equals("/stall")){try{Thread.sleep(4000);}catch(InterruptedException ignored){}data=media;}
                else if(path.equals("/redirect")){x.getResponseHeaders().add("Location","/fast/index.m3u8");x.sendResponseHeaders(302,-1);return;}
                else if(path.equals("/feed"))data=("#EXTM3U\n#EXTINF:-1,CCTV5HD\n"+base+"/fast/index.m3u8\n#EXTINF:-1,Other Station\n"+base+"/fast/index.m3u8\n").getBytes("UTF-8");
                else if(path.equals("/master"))data="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhtml\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nfast/index.m3u8\n".getBytes("UTF-8");
                else if(path.equals("/html"))data="<html>not video</html>".getBytes("UTF-8");
                else if(path.endsWith("index.m3u8")){
                    StringBuilder text=new StringBuilder("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n");if(path.startsWith("/range/"))text.append("#EXT-X-BYTERANGE:100@0\n");
                    for(int i=0;i<9;i++)text.append("#EXTINF:").append(path.startsWith("/burst/")?"0.10":"2.0").append(",\ns").append(i).append(".ts\n");text.append("#EXT-X-ENDLIST\n");data=text.toString().getBytes("UTF-8");
                }else if(path.endsWith(".ts")){
                    data=media.clone();if(path.startsWith("/baddecode/"))data[data.length-1]='B';
                    if(path.startsWith("/moderate/"))try{Thread.sleep(55);}catch(InterruptedException ignored){}
                    if(path.startsWith("/partialrange/")){x.getResponseHeaders().add("Content-Range","bytes 0-196647/393296");x.sendResponseHeaders(206,data.length);x.getResponseBody().write(data);return;}
                    if(path.startsWith("/truncated/")){x.sendResponseHeaders(200,data.length*2);x.getResponseBody().write(data,0,1024);return;}
                    if(path.startsWith("/burst/")){x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data,0,32768);x.getResponseBody().flush();try{Thread.sleep(180);}catch(InterruptedException ignored){}x.getResponseBody().write(data,32768,data.length-32768);return;}
                }else{x.sendResponseHeaders(404,-1);return;}
                x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);
            }finally{x.close();}
        }});server.start();
        try{
            check(SourceInspector.key("央视五套·体育").equals(SourceInspector.key("CCTV-5 HD")),"canonical CCTV aliases merge");
            check(!SourceInspector.key("CCTV5+").equals(SourceInspector.key("CCTV5"))&&!SourceInspector.key("CCTV4K").equals(SourceInspector.key("CCTV4")),"distinct CCTV editions stay distinct");
            SourceInspector.Result feed=scan(engine(),Arrays.asList(c("央视五套·体育",base+"/dead"),c("Local",base+"/html"),c("CCTV5",base+"/fast/index.m3u8")),EMPTY,new String[]{base+"/feed"},15000,null);
            check(feed.entries.size()==2&&feed.entries.get(0).channel.name.equals("央视五套·体育"),"one displayed channel per station, retaining first name and order");
            check(feed.entries.get(0).health.ok&&feed.entries.get(0).health.samples==8&&feed.entries.get(0).health.frames==30,"two rounds of eight complete distinct samples plus decode required");
            check(!feed.entries.get(1).health.ok&&feed.entries.get(1).health.testedAt>0,"unqualified station retains position and completed failure record");
            check(feed.feedsOk==1&&feed.entries.get(0).channel.url.equals(base+"/fast/index.m3u8"),"feed repairs current station without adding unrelated stations");
            int beforeFast=hits.get("/fast/index.m3u8").get();
            SourceInspector.Result ranked=scan(engine(),Arrays.asList(c("Compare",base+"/moderate/index.m3u8")),Arrays.asList(c("Compare",base+"/fast/index.m3u8")),new String[0],15000,null);
            check(ranked.entries.get(0).health.ok&&ranked.entries.get(0).channel.url.contains("/fast/"),"compare every candidate even when original already passes");
            check(hits.get("/fast/index.m3u8").get()>beforeFast&&ranked.entries.get(0).health.routes==2,"route winner selected only after all candidates complete");
            SourceInspector.Result master=scan(engine(),Arrays.asList(c("Master",base+"/master"),c("Redirect",base+"/redirect")),EMPTY,new String[0],15000,null);
            check(master.usable==2,"highest bandwidth master variant and relative redirect base handled");
            SourceInspector.Result burst=scan(engine(),Arrays.asList(c("Burst",base+"/burst/index.m3u8")),EMPTY,new String[0],10000,null);
            check(!burst.entries.get(0).health.ok,"fast first 32KB cannot hide slow remainder of full segment");
            SourceInspector.Result bad=scan(engine(),Arrays.asList(c("Decode",base+"/baddecode/index.m3u8"),c("Truncated",base+"/truncated/index.m3u8"),c("Range",base+"/range/index.m3u8"),c("Partial",base+"/partialrange/index.m3u8")),EMPTY,new String[0],10000,null);
            check(bad.usable==0,"decode failure, truncated payload, partial HTTP 206, and unsupported byte ranges cannot qualify");
            final ConcurrentHashMap<String,SourceInspector.RouteRecord> routes=new ConcurrentHashMap<String,SourceInspector.RouteRecord>();
            final AtomicReference<SourceInspector> first=new AtomicReference<SourceInspector>();first.set(engine());
            final AtomicInteger stationCompletions=new AtomicInteger();
            SourceInspector.Listener roundCheckpoint=new SourceInspector.Listener(){public void progress(int a,int b,int c){}public void sampled(String id,SourceInspector.RouteRecord r){routes.put(id,r);if(r.round==1&&r.completedAt==0)first.get().cancel();}public void completed(SourceInspector.Entry e){stationCompletions.incrementAndGet();}};
            SourceInspector.Result partial=scan(first.get(),Arrays.asList(c("Resume",base+"/fast/index.m3u8")),EMPTY,new String[0],10000,roundCheckpoint);
            check(partial.partial&&partial.entries.get(0).health.testedAt==0&&stationCompletions.get()==0,"pause after round one never stamps station complete");
            SourceInspector.RouteRecord checkpoint=routes.values().iterator().next();check(checkpoint.round==1&&checkpoint.samples.size()==4,"first round and distinct identities persist");
            ByteArrayOutputStream serialized=new ByteArrayOutputStream();try(ObjectOutputStream out=new ObjectOutputStream(serialized)){out.writeObject(new HashMap<String,SourceInspector.RouteRecord>(routes));}
            Map<String,SourceInspector.RouteRecord> restored;try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))){restored=(Map<String,SourceInspector.RouteRecord>)in.readObject();}
            int[] counts=new int[9];for(int i=0;i<9;i++){AtomicInteger n=hits.get("/fast/s"+i+".ts");counts[i]=n==null?0:n.get();}
            SourceInspector resumed=new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),restored,DECODER);
            SourceInspector.Result resumedResult=scan(resumed,Arrays.asList(c("Resume",base+"/fast/index.m3u8")),EMPTY,new String[0],10000,null);int added=0;for(int i=0;i<9;i++){AtomicInteger n=hits.get("/fast/s"+i+".ts");added+=(n==null?0:n.get())-counts[i];}
            check(resumedResult.usable==1&&resumedResult.entries.get(0).health.samples==8&&added==4,"serialized partial resumes only missing four segments");
            final HashMap<String,SourceInspector.Entry> saved=new HashMap<String,SourceInspector.Entry>();
            SourceInspector.Listener saveStation=new SourceInspector.Listener(){public void progress(int a,int b,int c){}public void completed(SourceInspector.Entry e){saved.put(SourceInspector.key(e.channel.name),e);}};
            scan(engine(),Arrays.asList(c("Fresh",base+"/fast/index.m3u8"),c("Failed",base+"/dead")),EMPTY,new String[0],10000,saveStation);
            int deadBefore=hits.get("/dead").get(),manifestBefore=hits.get("/fast/index.m3u8").get();
            SourceInspector cached=new SourceInspector(Collections.<String>emptySet(),saved,Collections.<String,SourceInspector.RouteRecord>emptyMap(),DECODER);
            SourceInspector.Result cachedResult=scan(cached,Arrays.asList(c("Fresh",base+"/dead"),c("Failed",base+"/fast/index.m3u8")),EMPTY,new String[0],10000,null);
            check(cachedResult.usable==1&&hits.get("/dead").get()==deadBefore&&hits.get("/fast/index.m3u8").get()==manifestBefore,"recent successes AND failures skipped for twelve hours");
            saved.get("FRESH").health.testedAt=System.currentTimeMillis()-SourceInspector.INTERVAL_MS;
            SourceInspector.Result expired=scan(new SourceInspector(Collections.<String>emptySet(),saved,Collections.<String,SourceInspector.RouteRecord>emptyMap(),DECODER),Arrays.asList(c("Fresh",base+"/dead")),EMPTY,new String[0],10000,null);
            check(!expired.entries.get(0).health.ok&&hits.get("/dead").get()>deadBefore,"expired station tested again");
            SourceInspector.Result timeout=scan(engine(),Arrays.asList(c("Budget",base+"/stall")),EMPTY,new String[0],80,null);
            check(timeout.partial&&timeout.entries.get(0).health.testedAt==0,"budget expiry stays pending rather than becoming a twelve-hour failure");
            long now=System.currentTimeMillis();check(SourceInspector.fresh(now-SourceInspector.INTERVAL_MS+1,now)&&!SourceInspector.fresh(now-SourceInspector.INTERVAL_MS,now)&&!SourceInspector.fresh(now+1,now),"twelve-hour boundary and clock rollback handled");
            check(rangeHits.get()==0&&decoderCalls.get()>0,"full downloads never use misleading small byte ranges and invoke decoder");
            SourceInspector stream=new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),Collections.<String,SourceInspector.RouteRecord>emptyMap(),STREAM_DECODER);
            SourceInspector.Result streamed=scan(stream,Arrays.asList(c("Stream",base+"/stream-redirect.ts")),EMPTY,new String[0],15000,null);
            check(streamed.usable==1&&streamed.entries.get(0).health.samples==8,"redirected continuous stream uses eight timestamp windows");
            final AtomicReference<SourceInspector> cancelledStream=new AtomicReference<SourceInspector>();final ConcurrentHashMap<String,SourceInspector.RouteRecord> interruptedWindows=new ConcurrentHashMap<String,SourceInspector.RouteRecord>();
            SourceInspector.Decoder pauseMetadata=new SourceInspector.Decoder(){public SourceInspector.SampleInfo analyze(byte[] b,boolean d,SourceInspector.Stop s)throws Exception{cancelledStream.get().cancel();return STREAM_DECODER.analyze(b,d,s);}};
            cancelledStream.set(new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),Collections.<String,SourceInspector.RouteRecord>emptyMap(),pauseMetadata));
            SourceInspector.Result interruptedWindow=scan(cancelledStream.get(),Arrays.asList(c("Window",base+"/continuous.ts")),EMPTY,new String[0],15000,new SourceInspector.Listener(){public void progress(int a,int b,int c){}public void sampled(String id,SourceInspector.RouteRecord r){interruptedWindows.put(id,r);}});
            check(interruptedWindow.partial&&interruptedWindows.values().iterator().next().samples.isEmpty(),"interruption while analyzing direct windows never persists a half-committed round");
            SourceInspector.Result streamResume=scan(new SourceInspector(Collections.<String>emptySet(),Collections.<String,SourceInspector.Entry>emptyMap(),interruptedWindows,STREAM_DECODER),Arrays.asList(c("Window",base+"/continuous.ts")),EMPTY,new String[0],15000,null);
            check(streamResume.usable==1&&streamResume.entries.get(0).health.samples==8,"direct stream resume commits exactly two complete rounds without duplicate windows");
            System.out.println(passed+" checks passed");
        }finally{server.stop(0);threads.shutdownNow();}
    }
}
