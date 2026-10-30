package it.voicescribe.recorder;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DocumentExportsTest {
    private Instrumentation instrument;private Context app;private File root;
    @Before public void setup()throws Exception{
        instrument=InstrumentationRegistry.getInstrumentation();app=instrument.getTargetContext();
        root=new File(app.getFilesDir(),"export-test-"+System.nanoTime());assertTrue(root.mkdir());
    }
    @After public void cleanup(){delete(root);}
    private void delete(File file){if(file==null)return;File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    private void waitForSaved(DocumentExports exports,File output,long length)throws Exception{
        long limit=System.currentTimeMillis()+15000;
        while((exports.busy()||output.length()!=length)&&System.currentTimeMillis()<limit)Thread.sleep(25);
        assertEquals("Export did not finish: "+exports.status(),length,output.length());assertFalse(exports.busy());assertFalse(exports.failed());
    }
    @Test public void documentResultAfterProcessRecreationRestoresItsSource()throws Exception{
        File source=new File(root,"source.wav"),target=new File(root,"export.wav"),journal=new File(root,"queue.json");Files.write(source.toPath(),new byte[]{1,2,3,4});
        CountDownLatch picker=new CountDownLatch(1);AtomicReference<DocumentExports> before=new AtomicReference<>();
        DocumentExports.Listener listener=new DocumentExports.Listener(){public boolean pick(DocumentExports.Entry entry){picker.countDown();return true;}public void changed(String message){}};
        instrument.runOnMainSync(()->{DocumentExports exports=new DocumentExports(app,journal);before.set(exports);exports.attach(listener);exports.prepare(()->new DocumentExports.Entry(source,"test.wav","audio/wav",false));});
        assertTrue(picker.await(10,TimeUnit.SECONDS));assertTrue(journal.isFile());
        // A new manager has none of the original Activity's Java fields.
        AtomicReference<DocumentExports> after=new AtomicReference<>();
        instrument.runOnMainSync(()->{DocumentExports exports=new DocumentExports(app,journal);after.set(exports);exports.result(Activity.RESULT_OK,Uri.fromFile(target),0);});
        try{waitForSaved(after.get(),target,4);assertArrayEquals(Files.readAllBytes(source.toPath()),Files.readAllBytes(target.toPath()));assertTrue(source.exists());}
        finally{before.get().io.shutdown();after.get().io.shutdown();}
    }
    @Test public void failedDestinationCanBeRetriedWithoutDeletingTheSource()throws Exception{
        File source=new File(root,"source.wav"),target=new File(root,"export.wav");Files.write(source.toPath(),new byte[]{1,2,3,4});
        CountDownLatch picker=new CountDownLatch(1);AtomicReference<DocumentExports> manager=new AtomicReference<>();
        DocumentExports.Listener listener=new DocumentExports.Listener(){public boolean pick(DocumentExports.Entry entry){picker.countDown();return true;}public void changed(String message){}};
        instrument.runOnMainSync(()->{DocumentExports exports=new DocumentExports(app,new File(root,"queue.json"));manager.set(exports);exports.attach(listener);exports.prepare(()->new DocumentExports.Entry(source,"test.wav","audio/wav",false));});
        assertTrue(picker.await(10,TimeUnit.SECONDS));
        instrument.runOnMainSync(()->manager.get().result(Activity.RESULT_OK,Uri.parse("content://it.voicescribe.missing/export"),Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        long limit=System.currentTimeMillis()+10000;while(!manager.get().failed()&&System.currentTimeMillis()<limit)Thread.sleep(25);
        assertTrue(manager.get().status(),manager.get().failed());assertEquals(4,source.length());
        instrument.runOnMainSync(()->{manager.get().retry();manager.get().result(Activity.RESULT_OK,Uri.fromFile(target),0);});
        try{waitForSaved(manager.get(),target,4);assertTrue(source.isFile());}finally{manager.get().io.shutdown();}
    }
    @Test public void activityRecreationAndBlockedTranscriptionCannotLeaveAnEmptyDocument()throws Exception{
        MainActivity activity=(MainActivity)instrument.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String id="session-"+System.currentTimeMillis();File session=new File(app.getFilesDir(),id);assertTrue(session.mkdir());
        File wav=new File(session,"000000000000.wav"),target=new File(root,"export.wav");
        try(WavFile out=new WavFile(wav)){out.write(new byte[32000],32000);}
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),picker=new CountDownLatch(1);
        Field workerField=MainActivity.class.getDeclaredField("worker");workerField.setAccessible(true);
        ((ExecutorService)workerField.get(activity)).execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(blocked.await(5,TimeUnit.SECONDS));
        DocumentExports exports=DocumentExports.get(app);
        DocumentExports.Listener listener=new DocumentExports.Listener(){public boolean pick(DocumentExports.Entry entry){picker.countDown();return true;}public void changed(String message){}};
        MainActivity recreated=null;
        try{
            instrument.runOnMainSync(()->{exports.attach(listener);activity.new RecorderBridge().exportAudio(id);});
            assertTrue("Export blocked behind transcription",picker.await(10,TimeUnit.SECONDS));
            Instrumentation.ActivityMonitor monitor=instrument.addMonitor(MainActivity.class.getName(),null,false);
            instrument.runOnMainSync(activity::recreate);recreated=(MainActivity)instrument.waitForMonitorWithTimeout(monitor,15000);instrument.removeMonitor(monitor);
            assertNotNull("Activity was not recreated",recreated);final MainActivity current=recreated;
            instrument.runOnMainSync(()->current.onActivityResult(30,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(target))));
            waitForSaved(exports,target,32044);assertEquals(32044,wav.length());
        }finally{
            release.countDown();final MainActivity current=recreated==null?activity:recreated;
            instrument.runOnMainSync(()->{
                android.view.View view=((android.view.ViewGroup)current.findViewById(android.R.id.content)).getChildAt(0);
                if(view instanceof android.webkit.WebView)((android.webkit.WebView)view).evaluateJavascript("localStorage.setItem('voicescribe_recordings',JSON.stringify(JSON.parse(localStorage.getItem('voicescribe_recordings')||'[]').filter(r=>r.nativeAudioSession!=="+org.json.JSONObject.quote(id)+")));clearAutosave();window.autosaveNow=()=>{};",null);
                current.finish();
            });delete(session);
        }
    }
}
