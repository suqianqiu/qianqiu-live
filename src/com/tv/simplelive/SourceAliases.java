package com.tv.simplelive;
import java.util.*;
/** Reviewed public snapshot aliases and known placeholder URLs. No device measurements. */
final class SourceAliases {
    private static final Map<String,String> NAMES=new HashMap<String,String>();
    private static final Set<String> BLOCKED=new HashSet<String>();
    static {
        NAMES.put(" CGTN","CGTN英语");
        NAMES.put(" 井研综合","井研综合");
        NAMES.put(" 人间卫视","台湾·人间卫视");
        NAMES.put(" 哈尔滨都市咨询","哈尔滨都市咨询");
        NAMES.put(" 德惠综合","德惠综合");
        NAMES.put(" 白山公共","白山公共");
        NAMES.put(" 舒兰新闻综合","舒兰新闻综合");
        NAMES.put(" 苏州生活资讯","苏州生活资讯");
        NAMES.put(" 辉南新闻综合","辉南新闻综合");
        NAMES.put(" 金川新闻综合","金川新闻综合");
        NAMES.put("Beautiful Life TV","台湾·人间卫视");
        NAMES.put("CCTV-9 纪录","央视九套·纪录");
        NAMES.put("CCTV1","央视一套·综合");
        NAMES.put("CCTV10","央视十套·科教");
        NAMES.put("CCTV11","央视十一套·戏曲");
        NAMES.put("CCTV12","央视十二套·社会与法");
        NAMES.put("CCTV14","央视十四套·少儿");
        NAMES.put("CCTV17","央视十七套·农业农村");
        NAMES.put("CCTV2","央视二套·财经");
        NAMES.put("CCTV3","央视三套·综艺");
        NAMES.put("CCTV5+","央视体育赛事");
        NAMES.put("CCTV9","央视九套·纪录");
        NAMES.put("CETV1","中国教育电视台");
        NAMES.put("CGTN","CGTN英语");
        NAMES.put("CGTN纪录","CGTN记录");
        NAMES.put("Supreme Master TV","台湾·无上师电视台");
        NAMES.put("TVBPearl","TVB明珠台");
        NAMES.put("WXTV (480p)","台湾·唯心电视台");
        NAMES.put("人间卫视","台湾·人间卫视");
        NAMES.put("凤凰中文","香港·凤凰中文");
        NAMES.put("凤凰资讯","香港·凤凰资讯");
        NAMES.put("唯心电视台","台湾·唯心电视台");
        NAMES.put("无上师电视台","台湾·无上师电视台");
        NAMES.put("无线新闻","香港·无线新闻");
        NAMES.put("澳视澳门","澳視澳門");
        BLOCKED.add("http://stream5.jlntv.cn/wq/playlist.m3u8");
        BLOCKED.add("http://stream5.jlntv.cn/wq/sd/live.m3u8");
        BLOCKED.add("http://stream8.jlntv.cn/cbstv/sd/live.m3u8?zjild");
        BLOCKED.add("http://stream8.jlntv.cn/dhtv/playlist.m3u8");
        BLOCKED.add("http://stream8.jlntv.cn/dhtv/playlist.m3u8?zjild");
    }
    static String resolve(String name){String value=NAMES.get(name);return value==null?name:value;}
    // This correction belongs to one reviewed URL, not every station labelled TVBS.
    static String routeName(String name,String url){return url.equals("https://7612-5516-affc-d88b.kylintv.tv/live/tvbshd_iphone.m3u8")?"TVBS新闻台":name;}
    static boolean rejected(String url){return BLOCKED.contains(url);}
}
