package it.voicescribe.recorder;

import android.media.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AudioImporterTest {
    private File dir;
    @Before public void prepare()throws Exception{dir=Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),"audio-import-").toFile();}
    @After public void clean(){remove(dir);}
    private static void remove(File file){if(file==null)return;File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    @Test(timeout=120000) public void wavOver25MegabytesIsStreamedWithoutLosingTheEnd()throws Exception{
        int bytes=27*1024*1024;File source=new File(dir,"source.audio");ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes("US-ASCII")).putInt(bytes+36).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)2).putInt(44100).putInt(44100*4).putShort((short)4).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(bytes);
        byte[] block=new byte[65536];for(int i=0;i<block.length;i+=2){block[i]=(byte)4000;block[i+1]=(byte)(4000>>8);}
        try(FileOutputStream out=new FileOutputStream(source)){out.write(h.array());for(int left=bytes;left>0;left-=Math.min(left,block.length))out.write(block,0,Math.min(left,block.length));}
        final double[] progress={0};AudioImporter.decode(source,dir,value->progress[0]=value);
        File[] parts=Transcriber.segments(dir);assertTrue(parts.length>2);long pcm=0;
        for(File part:parts){assertTrue(part.length()<1200000);assertEquals(pcm*1000/WavFile.BYTES_PER_SECOND,Long.parseLong(part.getName().replace(".wav","")));pcm+=part.length()-44;}
        assertEquals((long)Math.ceil((bytes/4L)*16000.0/44100)*2,pcm);assertEquals(1,progress[0],0);assertEquals(bytes+44,source.length());
        try(RandomAccessFile last=new RandomAccessFile(parts[parts.length-1],"r")){last.seek(last.length()-2);assertEquals(4000,Short.reverseBytes(last.readShort()));}
    }
    @Test(timeout=60000) public void compressedM4aUsesTheRealAndroidCodec()throws Exception{
        File source=new File(dir,"source.m4a");MediaFormat format=MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1);format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);format.setInteger(MediaFormat.KEY_BIT_RATE,64000);
        MediaCodec encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");MediaMuxer muxer=new MediaMuxer(source.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);boolean started=false;int track=-1,at=0;boolean inputDone=false,done=false;long deadline=System.currentTimeMillis()+20000;
        try{encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!done){assertTrue("AAC encoder stalled",System.currentTimeMillis()<deadline);
                if(!inputDone){int index=encoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer input=encoder.getInputBuffer(index);input.clear();input.order(ByteOrder.LITTLE_ENDIAN);int samples=Math.min(input.remaining()/2,16000-at);for(int i=0;i<samples;i++)input.putShort((short)(Math.sin((at+i)*2*Math.PI*440/16000)*9000));encoder.queueInputBuffer(index,0,samples*2,at*1000000L/16000,samples==0?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);at+=samples;if(samples==0)inputDone=true;}}
                int output=encoder.dequeueOutputBuffer(info,10000);if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.getOutputFormat());muxer.start();started=true;}else if(output>=0){if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer data=encoder.getOutputBuffer(output);data.position(info.offset);data.limit(info.offset+info.size);muxer.writeSampleData(track,data,info);}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;encoder.releaseOutputBuffer(output,false);}
            }
        }finally{encoder.stop();encoder.release();if(started)muxer.stop();muxer.release();}
        AudioImporter.decode(source,dir,value->{});long bytes=0;for(File part:Transcriber.segments(dir)){bytes+=part.length()-44;assertTrue(WavFile.hasSignal(part));}assertTrue(bytes>=WavFile.BYTES_PER_SECOND*.9);assertTrue(source.isFile());
    }
    @Test public void interruptedPreparationLeavesTheSourceForRetry()throws Exception{
        File source=new File(dir,"source.audio");try(WavFile out=new WavFile(source)){out.write(new byte[64000],64000);}
        try{AudioImporter.decode(source,dir,value->{throw new InterruptedIOException("stop");});fail("Should stop");}catch(InterruptedIOException expected){assertTrue(source.isFile());}
    }
}
