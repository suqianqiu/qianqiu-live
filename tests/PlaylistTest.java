import com.tv.simplelive.Playlist;
import java.util.*;
import java.io.*;

public class PlaylistTest {
    private static byte[] bytes(String text) throws Exception { return text.getBytes("UTF-8"); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        List<Playlist.Channel> channels = Playlist.parse(bytes("\uFEFF#EXTM3U\r\n#EXTINF:-1 group-title=\"新闻,本地\",频道,一\r\nstream/live.m3u8\r\n#EXTINF:-1,重复\r\nstream/live.m3u8\r\n"), "https://example.org/lists/main.m3u");
        check(channels.size() == 1, "Duplicate URLs should collapse");
        check(channels.get(0).name.equals("频道,一"), "Commas in attributes must not truncate the channel name");
        check(channels.get(0).group.equals("新闻,本地"), "Group title");
        check(channels.get(0).url.equals("https://example.org/lists/stream/live.m3u8"), "Relative URL resolution");
        channels = Playlist.parse(bytes("新闻,http://example.org/live?x=a,b\n#comment\n体育,https://example.org/sport"), "");
        check(channels.size() == 2 && channels.get(0).name.equals("新闻"), "TXT playlists");
        check(channels.get(0).url.endsWith("x=a,b"), "URL commas should remain intact");
        check(!Playlist.isHttp("javascript:alert(1)") && !Playlist.isHttp("http://user:pass@example.org/live"), "Only plain HTTP(S) addresses accepted");
        boolean rejected = false;
        try { Playlist.parse(bytes("#EXTM3U\n#EXT-X-TARGETDURATION:6\nsegment.ts"), "https://example.org/live.m3u8"); }
        catch (IOException e) { rejected = true; }
        check(rejected, "A media HLS manifest must not become a list of channels");
        rejected = false;
        try { Playlist.read(new ByteArrayInputStream(new byte[Playlist.MAX_BYTES + 1])); }
        catch (IOException e) { rejected = true; }
        check(rejected, "Enforce network/local byte bound");
        System.out.println("PASS: M3U metadata, relative URLs, deduplication, TXT, protocol filtering, HLS distinction, size bound");
    }
}
