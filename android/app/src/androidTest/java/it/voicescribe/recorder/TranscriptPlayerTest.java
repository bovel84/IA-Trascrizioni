package it.voicescribe.recorder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;import org.junit.runner.RunWith;import static org.junit.Assert.*;
import java.io.*;import java.nio.file.Files;import java.util.concurrent.CopyOnWriteArrayList;import java.util.concurrent.atomic.AtomicReference;
@RunWith(AndroidJUnit4.class)
public class TranscriptPlayerTest {
 @Test(timeout=12000) public void actualAndroidPlayerSeeksAndStopsAtTheRequestedExcerpt()throws Exception{
  android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
  File root=Files.createTempDirectory(instrumentation.getTargetContext().getCacheDir().toPath(),"review-player-").toFile(),session=new File(root,"session-1793091234567");assertTrue(session.mkdir());
  File wav=new File(session,"000000000000.wav");AtomicReference<TranscriptPlayer> player=new AtomicReference<>();CopyOnWriteArrayList<String> errors=new CopyOnWriteArrayList<>();
  try{
   try(InputStream in=instrumentation.getContext().getAssets().open("two-speakers.wav")){Files.copy(in,wav.toPath());}
   instrumentation.runOnMainSync(()->{try{TranscriptPlayer p=new TranscriptPlayer(root,errors::add);player.set(p);p.play("session-1793091234567/000000000000.wav#0",2000,2800);}catch(Exception e){errors.add(e.toString());}});
   long deadline=System.currentTimeMillis()+5000;while(!player.get().isPlaying()&&errors.isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
   assertEquals(errors.toString(),0,errors.size());assertTrue("Player did not start",player.get().isPlaying());
   deadline=System.currentTimeMillis()+3000;while(player.get().isPlaying()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
   assertFalse("Player continued beyond the requested excerpt",player.get().isPlaying());assertTrue(errors.toString(),errors.isEmpty());
  }finally{instrumentation.runOnMainSync(()->{if(player.get()!=null)player.get().close();});wav.delete();session.delete();root.delete();}
 }
}
