package it.voicescribe.recorder;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import static org.junit.Assert.*;

public class PcmResamplerTest {
    private byte[] convert(int rate,int channels,int encoding,byte[] bytes,int block)throws Exception{
        ByteArrayOutputStream result=new ByteArrayOutputStream();PcmResampler pcm=new PcmResampler(rate,channels,encoding,(data,length)->result.write(data,0,length));
        for(int at=0;at<bytes.length;at+=block)pcm.accept(ByteBuffer.wrap(bytes,at,Math.min(block,bytes.length-at)));pcm.finish();return result.toByteArray();
    }
    @Test public void splitDecoderBuffersPreserveEverySample()throws Exception{
        byte[] pcm=new byte[32002];for(int i=0;i<pcm.length;i++)pcm[i]=(byte)(i*31);
        assertArrayEquals(pcm,convert(16000,1,2,pcm,103));
    }
    @Test public void stereoDownmixAndFractionalRateKeepExactDuration()throws Exception{
        ByteBuffer input=ByteBuffer.allocate(44100*4+4).order(ByteOrder.LITTLE_ENDIAN);while(input.hasRemaining()){input.putShort((short)12000);input.putShort((short)4000);}
        byte[] result=convert(44100,2,2,input.array(),317);assertEquals((int)Math.ceil(44101*16000.0/44100)*2,result.length);
        ByteBuffer output=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);while(output.hasRemaining())assertEquals(8000,output.getShort());
    }
    @Test public void floatAndUnsignedPcmAreSupported()throws Exception{
        ByteBuffer input=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);input.putFloat(.25f).putFloat(-.25f).putFloat(Float.NaN).putFloat(1);
        ByteBuffer output=ByteBuffer.wrap(convert(16000,1,4,input.array(),3)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8192,output.getShort());assertEquals(-8192,output.getShort());assertEquals(0,output.getShort());assertEquals(32767,output.getShort());
        assertArrayEquals(new byte[]{0,-128,0,0,0,127},convert(16000,1,3,new byte[]{0,-128,-1},1));
    }
    @Test public void incompleteFramesFailInsteadOfSilentlyCuttingAudio()throws Exception{
        try{convert(48000,2,2,new byte[5],3);fail("Truncated PCM accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("incompleto"));}
    }
}
