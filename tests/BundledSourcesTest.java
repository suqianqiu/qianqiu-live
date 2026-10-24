package com.tv.simplelive;
import java.nio.file.*;
import java.util.*;
public final class BundledSourcesTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);System.out.println("PASS: "+text);}
    public static void main(String[] args)throws Exception{
        Path assets=Paths.get(args[0]);
        List<Playlist.Channel> catalog=Playlist.parse(Files.readAllBytes(assets.resolve("public-cn.m3u")),"https://example.org/list");
        List<Playlist.Channel> routes=Playlist.parse(Files.readAllBytes(assets.resolve("public-candidates-v3.m3u")),"https://example.org/list");
        check(catalog.size()==253&&routes.size()==485,"253 catalogue entries and 485 distinct candidate URLs parse");
        Set<String> keys=new HashSet<String>(),urls=new HashSet<String>();for(Playlist.Channel c:catalog){check(keys.add(SourceInspector.key(c.name)),"unique station: "+c.name);urls.add(c.url);}
        for(Playlist.Channel c:routes){check(keys.contains(SourceInspector.key(c.name)),"candidate belongs to catalogue: "+c.name);check(!SourceAliases.rejected(c.url),"no known placeholder: "+c.name);}
        check(!SourceInspector.key("CCTV4欧洲").equals(SourceInspector.key("CCTV4美洲")),"CCTV4 regional editions stay separate");
        check(!SourceInspector.key("TVBS").equals(SourceInspector.key("TVBS新闻台")),"TVBS main channel is not globally renamed as news");
        check(SourceAliases.routeName("TVBS","https://7612-5516-affc-d88b.kylintv.tv/live/tvbshd_iphone.m3u8").equals("TVBS新闻台"),"reviewed TVBS correction is URL-specific");
        check(SourceInspector.PUBLIC_FEEDS.length==11,"six new feeds plus five retained feeds");
        List<Playlist.Channel> regional=Playlist.parse(("#EXTM3U\n#EXTINF:-1 tvg-id=\"CCTV4Europe.cn\",CCTV4\nhttps://example.org/eu\n#EXTINF:-1 tvg-id=\"CCTV4America.cn\",CCTV4\nhttps://example.org/us\n").getBytes("UTF-8"),"");
        check(!SourceInspector.key(regional.get(0).name).equals(SourceInspector.key(regional.get(1).name)),"explicit regional tvg-id survives playlist parsing");
        System.out.println("Bundled sources checks passed");
    }
}
