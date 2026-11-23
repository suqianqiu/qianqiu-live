package com.tv.simplelive;

import java.util.*;

/** UI grouping only: never removes, renames or reorders the playback playlist. */
final class ChannelDrawer {
    private static final String[] REGIONS={"北京","天津","河北","山西","内蒙古","辽宁","吉林","黑龙江","上海","江苏","浙江","安徽","福建","江西","山东","河南","湖北","湖南","广东","广西","海南","重庆","四川","贵州","云南","西藏","陕西","甘肃","青海","宁夏","新疆"};
    // Known local station prefixes in the bundled list, where feeds only say "大陆".
    private static final String[] CITIES={
        "北京:中央电视台中塔东", "河北:任丘,兴隆,平泉,昌黎,清河,滦平,邢台,邯郸",
        "内蒙古:赤峰", "辽宁:朝阳", "吉林:四平,白城,珲春,辽源,通化,东辽,松原,梅河口,永吉,磐石,龙井,德惠,舒兰,辉南,白山",
        "黑龙江:七台河,哈尔滨", "上海:欢笑剧场,都市剧场,哈哈炫动",
        "江苏:新沂,苏州,句容", "浙江:中国蓝,之江,兰溪,开化,松阳,新昌,海宁,湖州,萧山,象山,青田,龙泉,龙游,诸暨,平湖",
        "安徽:亳州,祁门,歙县,黄山", "福建:FZTV,福州,云霄", "江西:南康", "山东:泰山",
        "广东:东莞,广州", "湖南:张家界", "四川:乐至,剑阁,广元,广安,松潘,汶川,沐川,荥经,营山,金川,青川,黑水,井研",
        "云南:楚雄,通海", "甘肃:兰州,嘉峪关,天祝,张掖,渭源,西峰"
    };
    static final class Group {
        final String name;
        final ArrayList<Integer> channels=new ArrayList<Integer>();
        final HashSet<String> keys=new HashSet<String>();
        Group(String name){this.name=name;}
        String progress(Set<String> completed){int done=0;for(String key:keys)if(completed.contains(key))done++;return done+"/"+keys.size();}
    }
    static final class Row {
        final Group group; final int channel; final String identity;
        Row(Group group,int channel,String identity){this.group=group;this.channel=channel;this.identity=identity;}
        boolean header(){return channel<0;}
    }
    private final ArrayList<String> channelKeys=new ArrayList<String>();
    final ArrayList<Group> groups=new ArrayList<Group>();
    ChannelDrawer(List<Playlist.Channel> channels){
        LinkedHashMap<String,Group> grouped=new LinkedHashMap<String,Group>();
        for(int i=0;i<channels.size();i++){
            Playlist.Channel c=channels.get(i);channelKeys.add(SourceInspector.key(c.name));String category=category(c);
            Group group=grouped.get(category);if(group==null){group=new Group(category);grouped.put(category,group);}
            group.channels.add(i);group.keys.add(SourceInspector.key(c.name));
        }
        groups.addAll(grouped.values());Collections.sort(groups,new Comparator<Group>(){public int compare(Group a,Group b){int d=rank(a.name)-rank(b.name);return d!=0?d:a.name.compareTo(b.name);}});
    }
    private static int rank(String name){if(name.equals("央视"))return 0;if(name.equals("卫视"))return 1;for(int i=0;i<REGIONS.length;i++)if(regionName(REGIONS[i]).equals(name))return i+2;if(name.equals("港澳台"))return 90;if(name.equals("海外"))return 91;return 99;}
    private static String regionName(String name){if(name.equals("北京")||name.equals("天津")||name.equals("上海")||name.equals("重庆"))return name+"市";if(name.equals("内蒙古")||name.equals("广西")||name.equals("西藏")||name.equals("宁夏")||name.equals("新疆"))return name;return name+"省";}
    private static boolean containsAny(String text,String...words){for(String word:words)if(text.contains(word))return true;return false;}
    static String category(Playlist.Channel c){
        String name=SourceAliases.resolve(c.name).trim(),group=c.group==null?"":c.group;
        String upper=name.toUpperCase(Locale.US);
        if(containsAny(group,"香港","澳门","澳門","台湾","臺灣","港澳台")||containsAny(name,"香港","台湾","臺灣","澳視","澳视")||upper.startsWith("TVB")||containsAny(name,"凤凰","鳳凰","翡翠","耀才","美亚","美亞"))return "港澳台";
        if(upper.startsWith("CCTV")||upper.startsWith("CGTN")||name.startsWith("央视")||name.startsWith("中国教育电视")||upper.startsWith("CETV")||group.contains("央视"))return "央视";
        if(containsAny(group,"国际","海外","国外")||containsAny(name,"美国之音","朝鲜","韩国","「SG」")||upper.startsWith("ABN"))return "海外";
        if(name.contains("卫视")||name.contains("衛視")||group.contains("卫视"))return "卫视";
        for(String region:REGIONS)if(group.contains(region)||name.startsWith(region))return regionName(region);
        for(String entry:CITIES){String[] parts=entry.split(":",2);for(String prefix:parts[1].split(","))if(name.startsWith(prefix))return regionName(parts[0]);}
        return "其他";
    }
    Group find(String name){for(Group group:groups)if(group.name.equals(name))return group;return null;}
    String categoryAt(int channel){for(Group group:groups)if(group.channels.contains(channel))return group.name;return null;}
    ArrayList<Row> rows(String expanded){ArrayList<Row> rows=new ArrayList<Row>();for(Group group:groups){rows.add(new Row(group,-1,"group:"+group.name));if(group.name.equals(expanded))for(int channel:group.channels)rows.add(new Row(group,channel,"channel:"+channelKeys.get(channel)));}return rows;}
}
