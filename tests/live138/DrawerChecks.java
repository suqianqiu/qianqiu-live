package com.tv.simplelive;
import java.util.*;
import java.nio.file.*;
public final class DrawerChecks {
    static int checks;
    static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static void category(String name,String group,String expected){require(ChannelDrawer.category(new Playlist.Channel(name,"http://fixture/"+name,group)).equals(expected),name+" -> "+expected);}
    public static void main(String[] args)throws Exception{
        category("央视一套·综合","大陆","央视");category("CCTV4欧洲","海外","央视");category("CGTN英语","大陆","央视");category("中国教育电视台","大陆","央视");
        category("湖南卫视","湖南频道","卫视");category("台湾·人间卫视","台湾","港澳台");category("凤凰中文","卫视频道","港澳台");category("TVB明珠台","大陆","港澳台");category("耀才财经","北京频道","港澳台");
        category("浙江经济生活","大陆","浙江省");category("杭州综合","浙江频道","浙江省");category("苏州4K","大陆","江苏省");category("珲春新闻综合","中国大陆 · 新闻","吉林省");category("北京科教","大陆","北京市");category("新疆二套","大陆","新疆");
        category("NHK World","海外","海外");category("韩国EBS 儿童频道","国际 · 儿童","海外");category("美国之音中文台","大陆","海外");category("未知频道","未知分类","其他");
        List<Playlist.Channel> channels=Playlist.parse(Files.readAllBytes(Paths.get(args[0])),"https://fixture/list");ChannelDrawer drawer=new ChannelDrawer(channels);Set<Integer> indices=new HashSet<Integer>();
        for(ChannelDrawer.Group group:drawer.groups)for(int i:group.channels)require(indices.add(i),"bundled channel assigned exactly once: "+channels.get(i).name);
        require(indices.size()==channels.size(),"all bundled channels retained");require(drawer.groups.get(0).name.equals("央视")&&drawer.groups.get(1).name.equals("卫视"),"national then satellite groups lead drawer");
        List<Playlist.Channel> duplicates=Arrays.asList(new Playlist.Channel("CCTV1","http://fixture/1","大陆"),new Playlist.Channel("CCTV1","http://fixture/2","大陆"),new Playlist.Channel("CCTV2","http://fixture/3","大陆"));ChannelDrawer d=new ChannelDrawer(duplicates);HashSet<String> completed=new HashSet<String>();completed.add(SourceInspector.key("CCTV1"));
        require(d.groups.get(0).progress(completed).equals("1/2"),"progress counts channel identities rather than route duplicates");require(d.rows(null).size()==1&&d.rows("央视").size()==4,"collapsed and expanded row counts preserve every route");
        require(d.rows("央视").get(3).channel==2,"drawer channel index maps to original playlist");
        System.out.println("RESULT PASS checks="+checks);
    }
}
