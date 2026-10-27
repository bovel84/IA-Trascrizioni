package it.voicescribe.recorder;
import org.junit.*;import static org.junit.Assert.*;import java.io.*;import java.nio.file.Files;
public class AudioExcerptTest {
 private File root,session,file;private final String token="session-1793091234567/000000030000.wav#2";
 @Before public void setup()throws Exception{root=Files.createTempDirectory("audio-excerpt-").toFile();session=new File(root,"session-1793091234567");assertTrue(session.mkdir());file=new File(session,"000000030000.wav");try(WavFile wav=new WavFile(file)){wav.write(new byte[32000],32000);}}
 @After public void clean(){for(File f:session.listFiles())f.delete();session.delete();root.delete();}
 @Test public void globalTranscriptTimesSelectTheCorrectAudioExcerpt()throws Exception{AudioExcerpt range=AudioExcerpt.resolve(root,token,30100,30500);assertEquals(file.getCanonicalFile(),range.file);assertEquals(100,range.start);assertEquals(500,range.end);}
 @Test public void endIsClampedToTheAvailableOriginalAudio()throws Exception{assertEquals(1000,AudioExcerpt.resolve(root,token,30100,99000).end);}
 @Test public void traversalAndArbitraryFilesAreRejected(){for(String id:new String[]{"../secret.wav","session-1793091234567/../../secret.wav","https://example.com/x.wav","session-1793091234567/voice-profiles.json"})assertThrows(IOException.class,()->AudioExcerpt.resolve(root,id,30100,30500));}
 @Test public void timesOutsideTheRecordingAreRejected(){assertThrows(IOException.class,()->AudioExcerpt.resolve(root,token,29900,30500));assertThrows(IOException.class,()->AudioExcerpt.resolve(root,token,31000,31500));assertThrows(IOException.class,()->AudioExcerpt.resolve(root,token,30100,30100));}
 @Test public void missingOriginalCannotBeReplacedWithAnotherRecording(){assertTrue(file.delete());assertThrows(IOException.class,()->AudioExcerpt.resolve(root,token,30100,30500));}
}
