package it.voicescribe.recorder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Real Android JSON/filesystem path: voice failures must not hide completed text. */
@RunWith(AndroidJUnit4.class)
public class TranscriptionIntegrityTest {
    private File session,wav;
    private static final String FULL="Prima frase, poi la seconda: sì, sì! Totale 12,50 €.";
    @Before public void prepare()throws Exception{
        session=Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(),"transcript-integrity-").toFile();
        VoiceData.write(new File(session,"native-voices.json"),new JSONObject().put("enabled",true));
        wav=new File(session,"000000000000.wav");
        try(WavFile audio=new WavFile(wav)){audio.write(new byte[WavFile.BYTES_PER_SECOND*4],WavFile.BYTES_PER_SECOND*4);}
    }
    @After public void clean(){remove(session);}
    private static void remove(File file){if(file==null)return;File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
    private String saved()throws Exception{return new String(Files.readAllBytes(Transcriber.result(wav).toPath()),StandardCharsets.UTF_8);}
    private JSONObject response()throws Exception{return new JSONObject().put("text",FULL).put("words",new JSONArray().put(new JSONObject().put("word",FULL).put("start",.1).put("end",3.5)));}
    private JSONObject unknown()throws Exception{
        JSONArray groups=VoiceData.completeGroups(wav,FULL);assertEquals(1,groups.length());JSONObject group=groups.getJSONObject(0);
        assertEquals(FULL,group.getString("text"));assertEquals(0,group.getInt("speaker"));assertTrue(group.getBoolean("review"));return group;
    }
    @Test public void corruptVoiceTimelineCannotBlockSavingTheFullTranscript()throws Exception{
        Files.write(VoiceData.timeline(wav).toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        Transcriber.saveResponse(wav,response());assertEquals(FULL,saved());unknown();
    }
    @Test public void missingVoiceTimelineStillRequestsAndStoresNativeResults()throws Exception{
        Transcriber.saveResponse(wav,response());assertTrue(VoiceData.response(wav).isFile());assertEquals(FULL,saved());unknown();
    }
    @Test public void malformedWordObjectsCannotHideTheTranscript()throws Exception{
        JSONObject json=response().put("words",new JSONArray().put("bad word metadata"));
        Transcriber.saveResponse(wav,json);assertEquals(FULL,saved());unknown();
    }
    @Test public void attributionWriteFailureCannotBlockTheTranscript()throws Exception{
        assertTrue(VoiceData.utterances(wav).mkdir());
        Transcriber.saveResponse(wav,response());assertEquals(FULL,saved());assertTrue(VoiceData.response(wav).isFile());unknown();
    }
    @Test public void responseCacheWriteFailureCannotBlockTheTranscript()throws Exception{
        assertTrue(VoiceData.response(wav).mkdir());
        Transcriber.saveResponse(wav,response());assertEquals(FULL,saved());
        Transcriber.transcribe(wav,"","","");assertEquals(FULL,saved()); // Existing TXT must not need an API key/re-upload.
    }
    @Test public void retryRecoversAnOldCachedResponseDespiteBrokenVoiceMetadata()throws Exception{
        VoiceData.write(VoiceData.response(wav),response());
        Files.write(VoiceData.timeline(wav).toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        Transcriber.transcribe(wav,"","","");assertEquals(FULL,saved());unknown();
    }
    private void cacheGroup(String text)throws Exception{
        VoiceData.write(VoiceData.utterances(wav),new JSONObject().put("groups",new JSONArray().put(new JSONObject().put("start",100).put("end",1000).put("speaker",1).put("text",text))));
    }
    @Test public void partialPersistedVoiceGroupsCannotReplaceTheFullText()throws Exception{
        cacheGroup("Prima frase,");unknown();
    }
    @Test public void punctuationAndNumbersCannotDisappearFromCachedGroups()throws Exception{
        cacheGroup(FULL.replace("12,50","1250").replace(":",""));unknown();
    }
    @Test public void invalidCachedTurnTimesCannotHideTheTranscript()throws Exception{
        cacheGroup(FULL);JSONObject data=VoiceData.read(VoiceData.utterances(wav));data.getJSONArray("groups").getJSONObject(0).put("end",999999);VoiceData.write(VoiceData.utterances(wav),data);unknown();
    }
    @Test public void validCompleteVoiceGroupsRemainAttributed()throws Exception{
        VoiceData.write(VoiceData.timeline(wav),new JSONObject().put("turns",new JSONArray().put(new JSONObject().put("start",0).put("end",4000).put("speaker",2).put("review",false))));
        Transcriber.saveResponse(wav,response());JSONArray groups=VoiceData.completeGroups(wav,FULL);
        assertEquals(FULL,saved());assertEquals(FULL,groups.getJSONObject(0).getString("text"));assertEquals(2,groups.getJSONObject(0).getInt("speaker"));
    }
}
