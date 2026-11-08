package com.tv.simplelive;
public final class RecoveryPolicyTest {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS: "+label);}
    public static void main(String[] args){
        PlaybackRecovery p=new PlaybackRecovery();p.start(0);p.clock(0,0);
        check(p.stalled(11999)==null,"short buffer does not reconnect");
        check(p.stalled(12000)!=null,"frozen clock reconnects after 12 seconds");
        check(p.nextDelay()==1000&&p.attempts()==1,"first retry delayed one second");
        p.start(13000);p.clock(1,13000);p.frames(10,13000);p.frames(20,14000);p.clock(5000,31000);
        check(p.stalled(31000)==null,"advancing video and clock accepted before frame threshold");
        p.clock(6000,32000);check(p.stalled(32000)!=null,"frozen video detected while audio clock advances");
        check(p.nextDelay()==3000,"second retry backs off three seconds");p.start(33000);
        check(p.nextDelay()==6000&&p.nextDelay()==-1,"third retry backs off and then stops");
        p.start(40000);for(long t=40000;t<=100000;t+=2000){p.clock(t,t);p.stalled(t);}
        check(p.attempts()==0,"minute of stable progress replenishes retry budget");
        p.nextDelay();p.reset();p.start(200000);p.frames(0,200000);p.frames(0,210000);p.clock(10,225000);
        check(p.stalled(225000)==null,"unsupported zero frame counters do not cause false recovery");
        check(p.attempts()==0,"explicit channel change resets retry budget");
        p.start(100);p.clock(1,100);p.clock(1,12100);check(p.stalled(12100)!=null,"duplicate clock events cannot mask frozen playback");
        p.start(20000);p.clock(100,20000);p.clock(10,25000);check(p.stalled(30000)==null,"clock discontinuity counts as progress");
        check(!PlaybackRecovery.finite("https://fixture/live.m3u8",false,120000),"positive HLS window duration does not turn live EOF into normal finish");
        check(PlaybackRecovery.finite("https://fixture/demo.mp4?token=1",false,0),"finite movie ends normally");
        check(PlaybackRecovery.finite("https://fixture/movie.m3u8",true,18000),"explicit finite direct video is not looped");
        check(!PlaybackRecovery.finite("https://fixture/live.ts",false,0),"live transport stream EOF reconnects");
        System.out.println(checks+" checks passed");
    }
}
