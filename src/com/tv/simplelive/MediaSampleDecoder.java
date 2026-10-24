package com.tv.simplelive;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/** Decode downloaded bytes off screen. Never attaches to or changes the user's VLC player. */
final class MediaSampleDecoder implements SourceInspector.Decoder {
    private final File cache;
    MediaSampleDecoder(Context context){cache=context.getCacheDir();}
    public synchronized SourceInspector.SampleInfo analyze(byte[] bytes,boolean decode,SourceInspector.Stop stop)throws Exception{
        File sample=File.createTempFile("source-probe-",".media",cache);
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;boolean started=false;
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try{
            check(stop,end);
            if(bytes==null||bytes.length<1024)throw new IOException("解码样本不足");
            try(FileOutputStream out=new FileOutputStream(sample)){out.write(bytes);}
            extractor.setDataSource(sample.getAbsolutePath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("video/")){track=i;format=f;break;}}
            if(track<0)throw new IOException("没有可识别的视频轨道");extractor.selectTrack(track);
            long first=Long.MAX_VALUE,last=Long.MIN_VALUE;int packets=0;
            while(extractor.getSampleTime()>=0&&packets++<50000){
                check(stop,end);long pts=extractor.getSampleTime();first=Math.min(first,pts);last=Math.max(last,pts);if(!extractor.advance())break;
            }
            if(first==Long.MAX_VALUE||last<=first)throw new IOException("视频时间戳不足");
            double frameSeconds=1.0/30;
            if(format.containsKey(MediaFormat.KEY_FRAME_RATE)){int fps=format.getInteger(MediaFormat.KEY_FRAME_RATE);if(fps>0)frameSeconds=1.0/fps;}
            if(!decode)return new SourceInspector.SampleInfo(first/1e6,last/1e6+frameSeconds,0);
            extractor.seekTo(first,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String mime=format.getString(MediaFormat.KEY_MIME),software=null;
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
                String name=info.getName();if(info.isEncoder()||!(name.startsWith("OMX.google.")||name.startsWith("c2.android.")))continue;
                for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase(mime)){software=name;break;}
                if(software!=null)break;
            }
            codec=software==null?MediaCodec.createDecoderByType(mime):MediaCodec.createByCodecName(software);
            codec.configure(format,null,null,0);codec.start();started=true;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean inputDone=false;int frames=0;
            while(frames<30){
                check(stop,end);
                if(!inputDone){int slot=codec.dequeueInputBuffer(10000);if(slot>=0){ByteBuffer buffer=codec.getInputBuffer(slot);if(buffer==null)throw new IOException("解码输入不可用");buffer.clear();int size=extractor.readSampleData(buffer,0);if(size<0){codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{long time=extractor.getSampleTime();codec.queueInputBuffer(slot,0,size,time,0);extractor.advance();}}}
                int slot=codec.dequeueOutputBuffer(info,10000);
                if(slot>=0){if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0)frames++;boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(slot,false);if(eos)break;}
            }
            return new SourceInspector.SampleInfo(first/1e6,last/1e6+frameSeconds,frames);
        }finally{
            if(codec!=null){if(started)try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}
            try{extractor.release();}catch(Exception ignored){}sample.delete();
        }
    }
    private static void check(SourceInspector.Stop stop,long end)throws IOException{
        if(stop.stopped()||System.nanoTime()>=end)throw new InterruptedIOException("解码验证暂停或超时");
    }
}
