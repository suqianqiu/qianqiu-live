package com.tv.simplelive;

/** Monotonic playback health and bounded retries; no Android/native calls. */
final class PlaybackRecovery {
    static final long CLOCK_TIMEOUT=12000,FRAME_TIMEOUT=18000,STABLE_WINDOW=60000;
    private long lastClockAt,lastFrameAt,lastClock=-1,healthySince=-1;
    private int lastFrames=-1,attempts;
    private boolean framesAdvancing;
    void reset(){attempts=0;healthySince=-1;}
    void start(long now){lastClockAt=lastFrameAt=now;lastClock=-1;lastFrames=-1;framesAdvancing=false;healthySince=-1;}
    void clock(long value,long now){if(value>=0&&value!=lastClock){lastClock=value;lastClockAt=now;if(healthySince<0)healthySince=now;}}
    void frames(int value,long now){if(value<0)return;if(lastFrames>=0&&value>lastFrames)framesAdvancing=true;if(value!=lastFrames){lastFrames=value;lastFrameAt=now;}}
    String stalled(long now){
        if(now-lastClockAt>=CLOCK_TIMEOUT)return "播放进度停止";
        if(framesAdvancing&&now-lastFrameAt>=FRAME_TIMEOUT)return "视频画面停止更新";
        if(healthySince>=0&&now-healthySince>=STABLE_WINDOW){attempts=0;healthySince=now;}
        return null;
    }
    long nextDelay(){healthySince=-1;if(attempts>=3)return -1;return new long[]{1000,3000,6000}[attempts++];}
    int attempts(){return attempts;}
    static boolean finite(String address,boolean direct,long duration){
        String path=address.split("[?#]",2)[0];
        return (direct&&duration>0)||path.matches("(?i).*\\.(mp4|m4v|mkv|webm|avi|mov)$");
    }
}
