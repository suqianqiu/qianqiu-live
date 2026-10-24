package com.tv.simplelive;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/** M3U and simple name,url lists; bounded, UTF-8, no credentials or custom auth. */
public final class Playlist {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CHANNELS = 20000;
    public static final class Channel {
        public final String name, url, group;
        public Channel(String name, String url, String group) {
            this.name = name; this.url = url; this.group = group;
        }
        @Override public String toString() { return name; }
    }
    public static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > MAX_BYTES) throw new IOException("列表超过 4 MB，请先精简频道");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
    public static boolean isHttp(String value) {
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }
    public static byte[] download(String address) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!isHttp(address)) throw new IOException("请输入有效的 HTTP/HTTPS 地址（不支持内嵌账号密码）");
            HttpURLConnection conn = (HttpURLConnection)new URL(address).openConnection();
            conn.setConnectTimeout(12000); conn.setReadTimeout(18000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "SimpleLiveTV/1.0");
            try {
                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null) throw new IOException("重定向缺少地址");
                    String next = new URL(new URL(address), location).toString();
                    if (address.startsWith("https:") && next.startsWith("http:"))
                        throw new IOException("列表服务器将 HTTPS 降级至 HTTP，已停止");
                    address = next; continue;
                }
                if (status != 200) throw new IOException("服务器返回 HTTP " + status);
                try (InputStream input = conn.getInputStream()) { return read(input); }
            } finally { conn.disconnect(); }
        }
        throw new IOException("重定向次数过多");
    }
    public static List<Channel> parse(byte[] bytes, String base) throws IOException {
        String text = new String(bytes, "UTF-8").replace("\uFEFF", "");
        List<Channel> result = new ArrayList<Channel>();
        Set<String> seen = new HashSet<String>();
        Pattern groupPattern = Pattern.compile("group-title=\"([^\"]*)\"");
        String name = "", group = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("#EXT-X-")) throw new IOException("这是单个 HLS 视频地址，请用“直接播放地址”打开");
            if (line.startsWith("#EXTINF:")) {
                boolean quoted = false; int comma = -1;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == '"') quoted = !quoted;
                    if (line.charAt(i) == ',' && !quoted) { comma = i; break; }
                }
                name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                // Regional CCTV4 editions can share a display name; preserve their explicit ID.
                Matcher regional = Pattern.compile("tvg-id=\"CCTV4(Europe|America)\\.cn\"",Pattern.CASE_INSENSITIVE).matcher(line);
                if(regional.find()) name = regional.group(1).equalsIgnoreCase("Europe")?"CCTV4欧洲":"CCTV4美洲";
                Matcher matcher = groupPattern.matcher(line);
                group = matcher.find() ? matcher.group(1) : "";
                continue;
            }
            if (line.startsWith("#")) continue;
            String url = line;
            int separator = line.indexOf(',');
            if (!isHttp(line) && separator > 0 && isHttp(line.substring(separator + 1).trim())) {
                name = line.substring(0, separator).trim(); url = line.substring(separator + 1).trim();
            } else if (!isHttp(url) && isHttp(base)) {
                try { url = new URL(new URL(base), url).toString(); } catch (Exception ignored) { continue; }
            }
            if (isHttp(url) && seen.add(url)) {
                result.add(new Channel(name.isEmpty() ? "频道 " + (result.size() + 1) : name, url, group));
                if (result.size() >= MAX_CHANNELS) break;
            }
            name = ""; group = "";
        }
        if (result.isEmpty()) throw new IOException("没有找到可用的 HTTP/HTTPS 频道地址");
        return result;
    }
}
