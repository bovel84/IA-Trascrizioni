package it.voicescribe.recorder;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class AudioExportTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File source()throws Exception {File file=temp.newFile();Files.write(file.toPath(),new byte[]{1,2,3,4});return file;}

    @Test public void copyReturnsOnlyAfterClosingTheDestination()throws Exception{
        File source=source();ByteArrayOutputStream target=new ByteArrayOutputStream();boolean[] closed={false};
        long bytes=AudioExport.copy(source,4,()->new FilterOutputStream(target){public void close()throws IOException{closed[0]=true;super.close();}});
        assertEquals(4,bytes);assertTrue(closed[0]);assertArrayEquals(Files.readAllBytes(source.toPath()),target.toByteArray());assertTrue(source.isFile());
    }
    @Test public void nullProviderStreamIsAnErrorAndPreservesSource()throws Exception{
        File source=source();assertThrows(IOException.class,()->AudioExport.copy(source,4,()->null));assertEquals(4,source.length());
    }
    @Test public void providerClosingFailureCannotBeReportedAsSaved()throws Exception{
        File source=source();
        assertThrows(IOException.class,()->AudioExport.copy(source,4,()->new ByteArrayOutputStream(){public void close()throws IOException{throw new IOException("Provider failure");}}));
        assertEquals(4,source.length());
    }
    @Test public void interruptedWriteKeepsTheCompleteSource()throws Exception{
        File source=source();
        assertThrows(IOException.class,()->AudioExport.copy(source,4,()->new OutputStream(){public void write(int b)throws IOException{throw new IOException("Spazio esaurito");}}));
        assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(source.toPath()));
    }
    @Test public void emptyOrChangedSourcesNeverOpenADestination()throws Exception{
        File empty=temp.newFile();boolean[] opened={false};
        assertThrows(IOException.class,()->AudioExport.copy(empty,0,()->{opened[0]=true;return new ByteArrayOutputStream();}));
        assertThrows(IOException.class,()->AudioExport.copy(source(),100,()->{opened[0]=true;return new ByteArrayOutputStream();}));
        assertFalse(opened[0]);
    }
    @Test public void twoHourWavExportsAllPcmAndRetainsTheOriginalSegments()throws Exception{
        File session=temp.newFolder(),output=temp.newFolder();
        for(int i=0;i<240;i++){
            File wav=new File(session,String.format(Locale.ROOT,"%012d.wav",i*30000L));
            try(RandomAccessFile file=new RandomAccessFile(wav,"rw")){file.setLength(44+960000);file.seek(44);file.write(i);}
            WavFile.repair(wav);
        }
        File wav=AudioExport.combine(session,output);assertEquals(44+7200L*WavFile.BYTES_PER_SECOND,wav.length());
        try(RandomAccessFile file=new RandomAccessFile(wav,"r")){
            byte[] signature=new byte[4];file.readFully(signature);assertEquals("RIFF",new String(signature,java.nio.charset.StandardCharsets.US_ASCII));
            file.seek(44);assertEquals(0,file.readUnsignedByte());file.seek(44+239L*960000);assertEquals(239,file.readUnsignedByte());
        }
        assertEquals(240,Transcriber.segments(session).length);
    }
    @Test public void silentAudioExportsARealNonemptyWav()throws Exception{
        File session=temp.newFolder(),output=temp.newFolder(),source=new File(session,"000000000000.wav");
        try(WavFile wav=new WavFile(source)){wav.write(new byte[32000],32000);}
        File exported=AudioExport.combine(session,output);assertEquals(32044,exported.length());assertFalse(WavFile.hasSignal(exported));
    }
    @Test public void missingAudioDoesNotCreateAnEmptyExport()throws Exception{
        File session=temp.newFolder(),output=temp.newFolder();assertThrows(IOException.class,()->AudioExport.combine(session,output));
        try(WavFile ignored=new WavFile(new File(session,"000000000000.wav"))){}
        assertThrows(IOException.class,()->AudioExport.combine(session,output));assertEquals(0,output.listFiles().length);
    }
}
