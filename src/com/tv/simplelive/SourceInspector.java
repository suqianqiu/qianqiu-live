package com.tv.simplelive;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.text.Normalizer;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Complete delivery sampling, independent video decode, and resumable route records. */
final class SourceInspector {
    static final int SCHEMA=2,MAX_ROUTES=12;
    static final double MIN_HEADROOM=1.5;
    static final long INTERVAL_MS=12L*60*60*1000;
    static final String[] PUBLIC_FEEDS={"https://iptv-org.github.io/iptv/countries/cn.m3u","https://iptv-org.github.io/iptv/countries/hk.m3u","https://iptv-org.github.io/iptv/countries/mo.m3u","https://iptv-org.github.io/iptv/countries/tw.m3u","https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u","https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u","https://raw.githubusercontent.com/zilong7728/Collect-IPTV/main/best_sorted.m3u","https://raw.githubusercontent.com/hujingguang/ChinaIPTV/main/cnTV_AutoUpdate.m3u8","https://raw.githubusercontent.com/CCSH/IPTV/main/live.m3u","https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8","https://raw.githubusercontent.com/cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u"};
    interface Stop { boolean stopped(); }
    interface Decoder { SampleInfo analyze(byte[] media,boolean decode,Stop stop)throws Exception; }
    static final class SampleInfo {
        final double firstSeconds,lastSeconds;final int frames;
        SampleInfo(double a,double b,int f){firstSeconds=a;lastSeconds=b;frames=f;}
    }
    interface Listener {
        void progress(int done,int total,int usable);
        default void completed(Entry e){}
        default void candidates(List<Playlist.Channel> rows,int refreshed){}
        default void sampled(String id,RouteRecord r){}
    }
    static boolean fresh(long at,long now){return at>0&&now>=at&&now-at<INTERVAL_MS;}
    static final class Health {
        boolean ok,tested;long responseMs,bytesPerSecond,testedAt;
        double headroom;int samples,frames,routes;String state="未检测";
    }
    static final class Entry {
        final Playlist.Channel channel;final Health health;
        Entry(Playlist.Channel c,Health h){channel=c;health=h;}
    }
    static final class Result {
        final ArrayList<Entry> entries=new ArrayList<Entry>();
        final ArrayList<Playlist.Channel> candidates=new ArrayList<Playlist.Channel>();
        int groups,checked,usable,feedsOk,feedsFailed;boolean partial;
    }
    static final class Sample implements Serializable {
        private static final long serialVersionUID=1L;
        double duration,seconds;long bytes;
        Sample(double d,double s,long b){duration=d;seconds=s;bytes=b;}
    }
    static final class RouteRecord implements Serializable {
        private static final long serialVersionUID=2L;
        long startedAt,completedAt;int round,frames;boolean failed;
        String reason="",kind="hls";
        ArrayList<Sample> samples=new ArrayList<Sample>();HashSet<String> seen=new HashSet<String>();
        RouteRecord copy(){RouteRecord r=new RouteRecord();r.startedAt=startedAt;r.completedAt=completedAt;r.round=round;r.frames=frames;r.failed=failed;r.reason=reason;r.kind=kind;r.samples.addAll(samples);r.seen.addAll(seen);return r;}
    }
    private static final class Group {
        final Playlist.Channel original;final boolean existing;
        final ArrayList<Playlist.Channel> routes=new ArrayList<Playlist.Channel>();
        Group(Playlist.Channel c,boolean e){original=c;existing=e;routes.add(c);}
        void add(Playlist.Channel c){if(SourceAliases.rejected(c.url))return;for(Playlist.Channel r:routes)if(r.url.equals(c.url))return;if(routes.size()<MAX_ROUTES)routes.add(c);}
    }
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Set<HttpURLConnection> active=Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private final Set<String> priority;private final Map<String,Entry> previous;
    private final Map<String,RouteRecord> records;private final Decoder decoder;private final int concurrency;private long deadline;
    private final Stop stop=new Stop(){public boolean stopped(){return SourceInspector.this.stopped();}};
    SourceInspector(){this(Collections.<String>emptySet());}
    SourceInspector(Set<String> keys){this(keys,Collections.<String,Entry>emptyMap());}
    SourceInspector(Set<String> keys,Map<String,Entry> saved){this(keys,saved,Collections.<String,RouteRecord>emptyMap(),new Decoder(){public SampleInfo analyze(byte[] b,boolean d,Stop s)throws Exception{throw new IOException("解码验证不可用");}});}
    SourceInspector(Set<String> keys,Map<String,Entry> saved,Map<String,RouteRecord> routes,Decoder d){this(keys,saved,routes,d,3);}
    SourceInspector(Set<String> keys,Map<String,Entry> saved,Map<String,RouteRecord> routes,Decoder d,int parallel){priority=new HashSet<String>(keys);previous=new HashMap<String,Entry>(saved);records=new HashMap<String,RouteRecord>();for(Map.Entry<String,RouteRecord> e:routes.entrySet())records.put(e.getKey(),e.getValue().copy());decoder=d;concurrency=Math.max(1,Math.min(3,parallel));}
    void cancel(){cancelled.set(true);}
    void restoreRoutes(Map<String,RouteRecord> saved){for(Map.Entry<String,RouteRecord> e:saved.entrySet())records.put(e.getKey(),e.getValue().copy());}
    boolean stopped(){return cancelled.get()||Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline;}
    private void requireRunning()throws InterruptedIOException{if(stopped())throw new InterruptedIOException("巡检暂停");}
    static String key(String name) {
        String s=Normalizer.normalize(SourceAliases.resolve(name),Normalizer.Form.NFKC).toUpperCase(Locale.US).replace("央視","央视").replace("\u00a0","");
        if(Pattern.compile("CCTV[\\s_-]*4K").matcher(s).find())return "CCTV4K";
        if(Pattern.compile("CCTV[\\s_-]*8K").matcher(s).find())return "CCTV8K";
        if((s.contains("CCTV4")||s.startsWith("央视四套")||s.startsWith("中央四套"))&&(s.contains("EUROPE")||s.contains("欧洲")||s.contains("歐洲")))return "CCTV4/EU";
        if((s.contains("CCTV4")||s.startsWith("央视四套")||s.startsWith("中央四套"))&&(s.contains("AMERICA")||s.contains("美洲")))return "CCTV4/US";
        if(s.contains("央视体育赛事"))return "CCTV5+";
        String[] numbers={"一","二","三","四","五","六","七","八","九","十","十一","十二","十三","十四","十五","十六","十七"};
        for(int i=numbers.length-1;i>=0;i--)if(s.startsWith("央视"+numbers[i]+"套")||s.startsWith("中央"+numbers[i]+"套"))return "CCTV"+(i+1);
        Matcher m=Pattern.compile("CCTV[\\s_-]*(\\d{1,2})([+P]?)").matcher(s);
        if(m.find())return "CCTV"+Integer.parseInt(m.group(1))+(m.group(2).isEmpty()?"":"+");
        return s.replaceAll("\\([^)]*\\)|\\[[^]]*\\]","").replaceAll("超高清|超清|高清|标清|[248]K|1080[PI]?|720P|HD|SD","").replaceAll("[\\s·•_-]","");
    }
    static boolean better(Health a,Health b){return a.ok&&(b==null||a.headroom>b.headroom||(a.headroom==b.headroom&&a.responseMs<b.responseMs));}
    Result inspect(List<Playlist.Channel> original,List<Playlist.Channel> cached,String[] feeds,long budgetMs,final Listener listener)throws Exception{
        deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budgetMs);
        final LinkedHashMap<String,Group> groups=new LinkedHashMap<String,Group>();
        for(Playlist.Channel c:original){Group g=groups.get(key(c.name));if(g==null)groups.put(key(c.name),new Group(c,true));else g.add(c);}
        for(Playlist.Channel c:cached){Group g=groups.get(key(SourceAliases.routeName(c.name,c.url)));if(g!=null)g.add(c);}
        Result result=new Result();
        ExecutorService fetchers=Executors.newFixedThreadPool(3);ArrayList<Future<List<Playlist.Channel>>> fetched=new ArrayList<Future<List<Playlist.Channel>>>();
        try{for(final String feed:feeds)fetched.add(fetchers.submit(new Callable<List<Playlist.Channel>>(){public List<Playlist.Channel> call()throws Exception{Reply r=getReply(feed,Playlist.MAX_BYTES,Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(10)));return Playlist.parse(r.data,r.url);}}));for(Future<List<Playlist.Channel>> f:fetched){if(stopped())break;try{for(Playlist.Channel c:f.get(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())),TimeUnit.MILLISECONDS)){Group g=groups.get(key(SourceAliases.routeName(c.name,c.url)));if(g!=null)g.add(c);}result.feedsOk++;}catch(Exception e){if(!stopped())result.feedsFailed++;}}}finally{fetchers.shutdownNow();}
        for(Group g:groups.values())for(Playlist.Channel c:g.routes)result.candidates.add(new Playlist.Channel(g.original.name,c.url,g.original.group));
        if(listener!=null&&!stopped())listener.candidates(result.candidates,result.feedsOk);
        result.groups=groups.size();ArrayList<Group> work=new ArrayList<Group>(groups.values());
        Collections.sort(work,new Comparator<Group>(){public int compare(Group a,Group b){return Boolean.compare(!priority.contains(key(a.original.name)),!priority.contains(key(b.original.name)));}});
        final ConcurrentHashMap<String,Entry> choices=new ConcurrentHashMap<String,Entry>();final AtomicInteger done=new AtomicInteger(),good=new AtomicInteger();final int total=groups.size();ExecutorService probes=Executors.newFixedThreadPool(concurrency);ArrayList<Future<Entry>> jobs=new ArrayList<Future<Entry>>();
        try{
            // Each worker owns its manifest AND segment batch: never queue after acquiring expiring URLs.
            for(final Group g:work)jobs.add(probes.submit(new Callable<Entry>(){public Entry call(){Entry e=inspectGroup(g,listener);choices.put(key(g.original.name),e);if(e.health.ok)good.incrementAndGet();int count=done.incrementAndGet();if(listener!=null&&!stopped())listener.progress(count,total,good.get());return e;}}));
            for(Future<Entry> f:jobs)try{f.get(Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())),TimeUnit.MILLISECONDS);}catch(Exception e){if(stopped())break;}
            for(Group g:groups.values()){Entry e=choices.get(key(g.original.name));if(e==null){Health h=new Health();h.state="未完成，下次续测";e=new Entry(g.original,h);}if(g.existing||e.health.ok)result.entries.add(e);}
            for(Entry e:result.entries)if(e.health.testedAt>0){result.checked++;if(e.health.ok)result.usable++;}
            result.partial=stopped()||done.get()<groups.size();
        }finally{probes.shutdownNow();synchronized(active){for(HttpURLConnection c:active)c.disconnect();active.clear();}}
        return result;
    }
    private Entry inspectGroup(Group g,Listener listener){
        String k=key(g.original.name);Entry saved=previous.get(k);
        if(saved!=null&&fresh(saved.health.testedAt,System.currentTimeMillis()))return new Entry(new Playlist.Channel(g.original.name,saved.channel.url,g.original.group),saved.health);
        Health best=null;Playlist.Channel chosen=g.original;String failure="无合格源";
        for(Playlist.Channel route:g.routes){
            if(stopped()){Health h=new Health();h.state="比选未完成，下次续测";return new Entry(g.original,h);}
            Health h=probe(route,k,listener);if(h.testedAt==0){Health pending=new Health();pending.state="比选未完成，下次续测";return new Entry(g.original,pending);}
            if(better(h,best)){best=h;chosen=new Playlist.Channel(g.original.name,route.url,g.original.group);}if(!h.ok)failure=h.state;
        }
        if(best==null){best=new Health();best.tested=true;best.state="无合格源："+failure;}best.routes=g.routes.size();best.testedAt=System.currentTimeMillis();Entry e=new Entry(chosen,best);if(listener!=null&&!stopped())listener.completed(e);return e;
    }
    private Health health(RouteRecord r){
        Health h=new Health();h.tested=!r.samples.isEmpty()||r.completedAt>0;h.testedAt=r.completedAt;h.samples=r.samples.size();h.frames=r.frames;h.headroom=Double.POSITIVE_INFINITY;long bytes=0;double seconds=0;
        for(Sample s:r.samples){h.headroom=Math.min(h.headroom,s.duration/Math.max(.001,s.seconds));h.responseMs=Math.max(h.responseMs,(long)(s.seconds*1000));bytes+=s.bytes;seconds+=s.seconds;}
        if(r.samples.isEmpty())h.headroom=0;h.bytesPerSecond=(long)(bytes/Math.max(.001,seconds));h.ok=r.completedAt>0&&!r.failed&&r.samples.size()>=8&&h.headroom>=MIN_HEADROOM&&r.frames>=30;
        h.state=h.ok?String.format(Locale.US,"优选 %.2f倍 · %d帧",h.headroom,h.frames):r.completedAt==0?"未完成，下次续测":r.reason;return h;
    }
    private void checkpoint(String id,RouteRecord r,Listener l){if(l!=null)l.sampled(id,r.copy());}
    private Health probe(Playlist.Channel route,String group,Listener l){
        String id=group+"\n"+route.url;RouteRecord r=records.get(id);long now=System.currentTimeMillis();
        if(r!=null&&fresh(r.completedAt,now))return health(r);if(r==null||!fresh(r.startedAt,now)){r=new RouteRecord();r.startedAt=now;}
        byte[] last=null;
        try{
            requireRunning();if(SourceAliases.rejected(route.url))throw new IOException("已知无信号占位源，已排除");boolean direct=route.url.matches("(?i).*\\.(ts|flv)(?:[?].*)?$")||r.kind.equals("direct");
            if(direct){r.kind="direct";last=direct(route.url,id,r,l);}
            else{
                for(int round=Math.max(1,r.round+1);round<=2;round++){
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);int target=round*4;
                    while(r.samples.size()<target){
                        requireRunning();if(System.nanoTime()>=end)throw new IOException("完整分片样本不足");List<Segment> all=segments(route.url);ArrayList<Segment> fresh=new ArrayList<Segment>();for(Segment s:all)if(!r.seen.contains(s.id))fresh.add(s);
                        for(int i=Math.max(0,fresh.size()-4);i<fresh.size()&&r.samples.size()<target;i++){
                            Segment s=fresh.get(i);long started=System.nanoTime();byte[] bytes=getReply(s.url,8*1024*1024,Math.min(deadline,started+TimeUnit.SECONDS.toNanos(12))).data;double elapsed=(System.nanoTime()-started)/1e9;
                            if(bytes.length<1024||new String(bytes,0,Math.min(32,bytes.length),"UTF-8").trim().startsWith("<"))throw new IOException("响应不是视频分片");
                            last=decodePayload(s,bytes);r.samples.add(new Sample(s.duration,elapsed,bytes.length));r.seen.add(s.id);checkpoint(id,r,l);
                        }
                        if(fresh.isEmpty()){requireRunning();Thread.sleep(500);}
                    }
                    r.round=round;checkpoint(id,r,l);
                }
                if(last==null){List<Segment> all=segments(route.url);Segment s=all.get(all.size()-1);last=decodePayload(s,getReply(s.url,8*1024*1024,requestLimit()).data);}
            }
            requireRunning();if(health(r).headroom<MIN_HEADROOM){r.failed=true;r.reason="速度余量不足1.5倍";}
            else{SampleInfo info=decoder.analyze(last,true,stop);requireRunning();r.frames=info.frames;if(r.frames<30){r.failed=true;r.reason="解码未达到30帧";}}
            requireRunning();r.completedAt=System.currentTimeMillis();
        }catch(Exception e){if(!stopped()){r.failed=true;r.completedAt=System.currentTimeMillis();r.reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}}
        checkpoint(id,r,l);return health(r);
    }
    private long requestLimit(){return Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(12));}
    private static final class Segment {String url,id,init,key,iv;double duration;long sequence;}
    private static String attr(String line,String name){Matcher m=Pattern.compile("(?:^|[:,])"+name+"=\"([^\"]*)\"").matcher(line);return m.find()?m.group(1):null;}
    private List<Segment> segments(String address)throws Exception{
        for(int level=0;level<4;level++){
            Reply response=getReply(address,512*1024,requestLimit());String text=new String(response.data,"UTF-8").replace("\uFEFF","").trim();if(!text.startsWith("#EXTM3U"))throw new IOException("不是可深测的HLS清单");String[] lines=text.split("\\r?\\n");
            if(text.contains("#EXT-X-STREAM-INF")){
                String next=null;long largest=-1;for(int i=0;i<lines.length-1;i++)if(lines[i].startsWith("#EXT-X-STREAM-INF:")){Matcher b=Pattern.compile("(?:[:,])BANDWIDTH=(\\d+)").matcher(lines[i]);long bitrate=b.find()?Long.parseLong(b.group(1)):0;int j=i+1;while(j<lines.length&&(lines[j].trim().isEmpty()||lines[j].startsWith("#")))j++;if(j<lines.length&&bitrate>largest){largest=bitrate;next=new URL(new URL(response.url),lines[j].trim()).toString();}}if(next==null)throw new IOException("主清单缺少线路");address=next;continue;
            }
            if(text.contains("#EXT-X-BYTERANGE"))throw new IOException("暂不支持分段字节范围深测");
            ArrayList<Segment> result=new ArrayList<Segment>();double duration=-1;long sequence=0;String init=null,key=null,iv=null;
            for(String raw:lines){String line=raw.trim();
                if(line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))sequence=Long.parseLong(line.substring(line.indexOf(':')+1).trim());
                else if(line.startsWith("#EXT-X-MAP:")){String u=attr(line,"URI");if(u!=null)init=new URL(new URL(response.url),u).toString();}
                else if(line.startsWith("#EXT-X-KEY:")){if(line.contains("METHOD=NONE")){key=null;iv=null;}else if(line.contains("METHOD=AES-128")){String u=attr(line,"URI");if(u==null)throw new IOException("缺少AES密钥地址");key=new URL(new URL(response.url),u).toString();Matcher m=Pattern.compile("IV=(0[xX][0-9a-fA-F]+)").matcher(line);iv=m.find()?m.group(1):null;}else throw new IOException("不支持的加密格式");}
                else if(line.startsWith("#EXTINF:")){duration=Double.parseDouble(line.substring(8).split(",")[0]);if(!(duration>0)||Double.isInfinite(duration))throw new IOException("分片时长无效");}
                else if(!line.isEmpty()&&!line.startsWith("#")&&duration>0){Segment s=new Segment();s.url=new URL(new URL(response.url),line).toString();s.sequence=sequence++;s.id=s.sequence+":"+s.url;s.duration=duration;s.init=init;s.key=key;s.iv=iv;result.add(s);duration=-1;}
            }
            if(result.isEmpty())throw new IOException("没有完整分片");return result;
        }
        throw new IOException("清单嵌套过深");
    }
    private byte[] decodePayload(Segment s,byte[] data)throws Exception{
        if(s.key!=null){byte[] key=getReply(s.key,32,requestLimit()).data;if(key.length!=16)throw new IOException("AES密钥无效");byte[] iv=new byte[16];if(s.iv==null){long n=s.sequence;for(int i=15;i>=8;i--){iv[i]=(byte)n;n>>>=8;}}else{String hex=s.iv.substring(2);if(hex.length()>32)throw new IOException("AES IV无效");while(hex.length()<32)hex="0"+hex;for(int i=0;i<16;i++)iv[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);}Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));data=cipher.doFinal(data);}
        if(s.init!=null){byte[] init=getReply(s.init,1024*1024,requestLimit()).data;byte[] joined=Arrays.copyOf(init,init.length+data.length);System.arraycopy(data,0,joined,init.length,data.length);data=joined;}return data;
    }
    private byte[] direct(String address,String id,RouteRecord r,Listener l)throws Exception{
        byte[] last=null;
        for(int round=Math.max(1,r.round+1);round<=2;round++){
            requireRunning();long first=System.nanoTime(),limit=requestLimit();HttpURLConnection c=openStream(address,limit);ArrayList<Integer> ends=new ArrayList<Integer>(),sizes=new ArrayList<Integer>();ArrayList<Double> times=new ArrayList<Double>();
            try{if(c.getResponseCode()!=200)throw new IOException("连续流HTTP失败");try(InputStream in=c.getInputStream();ByteArrayOutputStream data=new ByteArrayOutputStream()){
                byte[] buf=new byte[16384];for(int w=0;w<4;w++){long start=w==0?first:System.nanoTime();int before=data.size();while(data.size()-before<2*1024*1024){requireRunning();if(System.nanoTime()>=limit)throw new IOException("连续流采样超时");int n=in.read(buf,0,Math.min(buf.length,2*1024*1024-(data.size()-before)));if(n<0)throw new IOException("连续流提前结束");data.write(buf,0,n);if(System.nanoTime()-start>=TimeUnit.SECONDS.toNanos(2))break;}ends.add(data.size());sizes.add(data.size()-before);times.add((System.nanoTime()-start)/1e9);}last=data.toByteArray();
            }}finally{active.remove(c);c.disconnect();}
            ArrayList<Sample> windows=new ArrayList<Sample>();double previous=-1;for(int i=0;i<4;i++){requireRunning();SampleInfo info=decoder.analyze(Arrays.copyOf(last,ends.get(i)),false,stop);double duration=info.lastSeconds-(previous<0?info.firstSeconds:previous);if(!(duration>0))throw new IOException("连续流时间戳不连续");previous=info.lastSeconds;windows.add(new Sample(duration,times.get(i),sizes.get(i)));}requireRunning();r.samples.addAll(windows);r.round=round;checkpoint(id,r,l);
        }
        if(last==null){long limit=requestLimit();HttpURLConnection c=openStream(address,limit);try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[16384];while(b.size()<2*1024*1024){requireRunning();if(System.nanoTime()>=limit)throw new IOException("连续流验证超时");int n=in.read(buf);if(n<0)break;b.write(buf,0,n);}last=b.toByteArray();}finally{active.remove(c);c.disconnect();}}
        return last;
    }
    private static final class Reply {final byte[] data;final String url;Reply(byte[] b,String u){data=b;url=u;}}
    private HttpURLConnection openStream(String address,long limit)throws Exception{
        for(int hop=0;hop<5;hop++){HttpURLConnection c=open(address,limit);boolean keep=false;try{int code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String u=c.getHeaderField("Location");if(u==null)throw new IOException("重定向无地址");address=new URL(new URL(address),u).toString();continue;}if(code!=200)throw new IOException("连续流HTTP "+code);keep=true;return c;}finally{if(!keep){active.remove(c);c.disconnect();}}}throw new IOException("重定向次数过多");
    }
    private HttpURLConnection open(String address,long limit)throws Exception{
        requireRunning();if(System.nanoTime()>=limit)throw new IOException("请求超时");if(!Playlist.isHttp(address))throw new IOException("地址格式无效");HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();int t=(int)Math.max(1,Math.min(2500,TimeUnit.NANOSECONDS.toMillis(limit-System.nanoTime())));c.setConnectTimeout(t);c.setReadTimeout(t);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","SimpleLiveTV/1.0");c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("Cache-Control","no-cache");active.add(c);return c;
    }
    private Reply getReply(String address,int max,long limit)throws Exception{
        for(int hop=0;hop<5;hop++){HttpURLConnection c=open(address,limit);try{int code=c.getResponseCode();if(code==301||code==302||code==303||code==307||code==308){String u=c.getHeaderField("Location");if(u==null)throw new IOException("重定向无地址");address=new URL(new URL(address),u).toString();continue;}if(code!=200&&code!=206)throw new IOException("HTTP "+code);if(code==206){String range=c.getHeaderField("Content-Range");if(range!=null){Matcher m=Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(range);if(!m.matches()||Long.parseLong(m.group(1))!=0||Long.parseLong(m.group(2))+1!=Long.parseLong(m.group(3)))throw new IOException("分片仅返回局部字节范围");}}try(InputStream in=c.getInputStream();ByteArrayOutputStream data=new ByteArrayOutputStream()){byte[] buf=new byte[16384];while(true){requireRunning();if(System.nanoTime()>=limit)throw new IOException("完整分片下载超时");int n=in.read(buf,0,Math.min(buf.length,max+1-data.size()));if(n<0)break;data.write(buf,0,n);if(data.size()>max)throw new IOException("完整分片超过大小上限");}int expected=c.getContentLength();if(expected>=0&&data.size()!=expected)throw new IOException("响应未完整下载");return new Reply(data.toByteArray(),address);}}finally{active.remove(c);c.disconnect();}}
        throw new IOException("重定向次数过多");
    }
    static byte[] playlist(List<Playlist.Channel> channels)throws Exception{StringBuilder text=new StringBuilder("#EXTM3U\n");for(Playlist.Channel c:channels)text.append("#EXTINF:-1 group-title=\"").append(c.group.replace("\"","").replace("\n","").replace("\r","")).append("\",").append(c.name.replace("\n","").replace("\r","")).append('\n').append(c.url).append('\n');return text.toString().getBytes("UTF-8");}
}
