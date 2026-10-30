package it.voicescribe.recorder;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.file.Files;import java.util.*;
public class AudioChunkPolicyTest {
 @Test public void quietAudioDoesNotCreateTinyBillableChunks(){AudioChunkPolicy p=new AudioChunkPolicy();for(int second=1;second<30;second++)assertFalse(p.shouldClose(second*32000L,32000,0));assertTrue(p.shouldClose(30*32000L,32000,0));}
 @Test public void speechAtThirtySecondsCanContinueToTheNextPause(){AudioChunkPolicy p=new AudioChunkPolicy();assertFalse(p.shouldClose(30*32000L,32000,4000));assertTrue(p.shouldClose(31*32000L,32000,0));}
 @Test public void uninterruptedSpeechCannotPostponeTheChunkIndefinitely(){AudioChunkPolicy p=new AudioChunkPolicy();assertFalse(p.shouldClose(34*32000L,32000,4000));assertTrue(p.shouldClose(35*32000L,32000,4000));}
 @Test public void rmsHandlesSignedSamplesAndDigitalSilence(){assertEquals(0,AudioChunkPolicy.rms(new byte[4],4),0);assertEquals(32767.5,AudioChunkPolicy.rms(new byte[]{-1,127,0,-128},4),1);}
 @Test public void concatenatedWavsRetainEveryOriginalPcmByte()throws Exception{
  File dir=Files.createTempDirectory("chunk-policy-").toFile();List<File> files=new ArrayList<>();byte[] original=new byte[32000*75];
  // Sustained speech with a gap just after the first nominal boundary.
  for(int i=0;i<original.length;i+=2){int second=i/32000;if(second==31||second==63)continue;short sample=(short)(Math.sin(i*.13)*6000);original[i]=(byte)sample;original[i+1]=(byte)(sample>>8);}
  WavFile current=null;long size=0;AudioChunkPolicy policy=new AudioChunkPolicy();
  try{
   for(int start=0;start<original.length;start+=4096){int n=Math.min(4096,original.length-start);byte[] part=Arrays.copyOfRange(original,start,start+n);
    if(current==null){File f=new File(dir,"part-"+files.size()+".wav");files.add(f);current=new WavFile(f);size=0;policy=new AudioChunkPolicy();}
    current.write(part,n);size+=n;if(policy.shouldClose(size,n,AudioChunkPolicy.rms(part,n))){current.close();current=null;}
   }
   if(current!=null){current.close();current=null;}ByteArrayOutputStream joined=new ByteArrayOutputStream();
   for(File f:files){byte[] bytes=Files.readAllBytes(f.toPath());joined.write(bytes,44,bytes.length-44);assertTrue(bytes.length<=44+32000*35+4096);}
   assertEquals(3,files.size());assertArrayEquals(original,joined.toByteArray());
  }finally{if(current!=null)current.close();for(File f:files)f.delete();dir.delete();}
 }
}
