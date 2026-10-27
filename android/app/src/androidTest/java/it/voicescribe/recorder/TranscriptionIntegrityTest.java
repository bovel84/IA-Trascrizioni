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
    @Test public void missingVoiceTimelineStillStoresTheFullResponse()throws Exception{
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
    @Test public void cachedTextRecoveryDoesNotDependOnVoiceSettingsOrSignalDetection()throws Exception{
        VoiceData.write(VoiceData.response(wav),response());
        assertTrue(new File(session,"native-voices.json").delete());assertFalse(WavFile.hasSignal(wav));
        Transcriber.transcribe(wav,"","","");assertEquals(FULL,saved());
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
    @Test public void poorAudioMetadataFlagsReviewWithoutDroppingAnyText()throws Exception{
        JSONObject json=response().put("segments",new JSONArray().put(new JSONObject().put("avg_logprob",-1.4).put("compression_ratio",3.2).put("no_speech_prob",.9)));
        Transcriber.saveResponse(wav,json);JSONObject quality=VoiceData.readQuality(wav);
        assertEquals(FULL,saved());assertTrue(quality.getBoolean("available"));assertTrue(quality.getBoolean("review"));assertEquals(1,quality.getInt("flaggedSegments"));assertTrue(quality.getString("reason").contains("ripetizioni"));
    }
    @Test public void missingMetadataNeverClaimsMeasuredAudioQuality()throws Exception{assertFalse(VoiceData.assess(response()).getBoolean("available"));assertFalse(VoiceData.assess(response()).getBoolean("review"));}
    @Test public void goodMetadataDoesNotInventAnAccuracyPercentage()throws Exception{
        JSONObject quality=VoiceData.assess(response().put("segments",new JSONArray().put(new JSONObject().put("avg_logprob",-.2).put("compression_ratio",1.3).put("no_speech_prob",.1))));
        assertTrue(quality.getBoolean("available"));assertFalse(quality.getBoolean("review"));assertFalse(quality.has("accuracy"));
    }
    @Test public void silenceProbabilityAloneNeverDiscardsConfidentSpeech()throws Exception{
        JSONObject quality=VoiceData.assess(response().put("segments",new JSONArray().put(new JSONObject().put("avg_logprob",-.1).put("no_speech_prob",.99))));assertFalse(quality.getBoolean("review"));
    }
    @Test public void oldCachedResultsGainReviewMetadataWithoutNetwork()throws Exception{
        VoiceData.write(VoiceData.response(wav),response().put("segments",new JSONArray().put(new JSONObject().put("avg_logprob",-2))));
        assertTrue(VoiceData.readQuality(wav).getBoolean("review"));assertTrue(VoiceData.quality(wav).isFile());
    }
    private File next(String offset)throws Exception{File file=new File(session,offset+".wav");Files.copy(wav.toPath(),file.toPath());return file;}
    @Test public void contiguousAudioReceivesTerminologyAndPreviousText()throws Exception{
        VoiceData.write(new File(session,"transcription-settings.json"),new JSONObject().put("glossary","SFIRS RAS"));Files.write(Transcriber.result(wav).toPath(),"La discussione prosegue nel prossimo segmento.".getBytes(StandardCharsets.UTF_8));
        String prompt=Transcriber.contextPrompt(next("000000004000"));assertTrue(prompt.startsWith("SFIRS RAS\n"));assertTrue(prompt.endsWith("segmento."));assertTrue(prompt.getBytes(StandardCharsets.UTF_8).length<=220);
    }
    @Test public void missingPrecedingTranscriptCannotUseFutureOrOlderChunks()throws Exception{
        Files.write(Transcriber.result(wav).toPath(),"Contesto vecchio".getBytes(StandardCharsets.UTF_8));File middle=next("000000004000"),last=next("000000008000");
        assertEquals("",Transcriber.contextPrompt(last));Files.write(Transcriber.result(last).toPath(),"Contesto futuro".getBytes(StandardCharsets.UTF_8));assertFalse(Transcriber.contextPrompt(middle).contains("futuro"));
    }
    @Test public void flaggedPreviousSpeechIsNotPropagatedAsContext()throws Exception{
        Files.write(Transcriber.result(wav).toPath(),"Testo da riascoltare".getBytes(StandardCharsets.UTF_8));VoiceData.write(VoiceData.quality(wav),new JSONObject().put("review",true));assertEquals("",Transcriber.contextPrompt(next("000000004000")));
    }
    @Test public void corruptedCacheCannotBypassAnUploadQuotaPause()throws Exception{
        Files.write(VoiceData.response(wav).toPath(),"{broken".getBytes(StandardCharsets.UTF_8));assertFalse(Transcriber.hasCachedResponse(wav));VoiceData.write(VoiceData.response(wav),response());assertTrue(Transcriber.hasCachedResponse(wav));
    }
    @Test public void smallSilenceInsideOneVoiceKeepsTheWordReviewableAndComplete()throws Exception{
        VoiceData.write(VoiceData.timeline(wav),new JSONObject().put("turns",new JSONArray()
            .put(new JSONObject().put("start",0).put("end",1400).put("speaker",1).put("review",false))
            .put(new JSONObject().put("start",1750).put("end",4000).put("speaker",1).put("review",false))));
        JSONObject response=new JSONObject().put("text","con").put("words",new JSONArray().put(new JSONObject().put("word","con").put("start",1.45).put("end",1.65)));
        Transcriber.saveResponse(wav,response);JSONObject group=VoiceData.completeGroups(wav,"con").getJSONObject(0);
        assertEquals("con",saved());assertEquals(1,group.getInt("speaker"));assertTrue(group.getBoolean("review"));assertEquals(1450,group.getLong("start"));assertEquals(1650,group.getLong("end"));
    }
}
