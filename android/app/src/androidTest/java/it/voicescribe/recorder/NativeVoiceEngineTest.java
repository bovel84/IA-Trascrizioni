package it.voicescribe.recorder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Runs the actual packaged JNI engine and ONNX models on Android; no service/API key. */
@RunWith(AndroidJUnit4.class)
public class NativeVoiceEngineTest {
    private Set<Integer> ids(File wav)throws Exception{
        JSONArray turns=VoiceData.read(VoiceData.timeline(wav)).getJSONArray("turns");Set<Integer> ids=new HashSet<>();
        for(int i=0;i<turns.length();i++){JSONObject turn=turns.getJSONObject(i);assertTrue(turn.getLong("end")>turn.getLong("start"));if(turn.getInt("speaker")>0)ids.add(turn.getInt("speaker"));}
        return ids;
    }
    @Test public void packagedModelsDistinguishRealVoicesAndRestoreTheirIds()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File session=new File(app.getCacheDir(),"native-voices-test-"+System.currentTimeMillis());assertTrue(session.mkdir());
        try{
            File first=new File(session,"000000000000.wav"),second=new File(session,"000000016000.wav");
            try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("two-speakers.wav");OutputStream out=new FileOutputStream(first)){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            java.nio.file.Files.copy(first.toPath(),second.toPath());
            try(NativeVoiceEngine engine=new NativeVoiceEngine(app,session)){engine.analyse(first);assertEquals(2,engine.voices());}
            Set<Integer> firstIds=ids(first);assertEquals(2,firstIds.size());
            try(NativeVoiceEngine engine=new NativeVoiceEngine(app,session)){engine.analyse(second);assertEquals(2,engine.voices());}
            assertEquals(firstIds,ids(second));
            VoiceData.rename(session,1,"Partecipante di prova");assertEquals("Partecipante di prova",VoiceData.names(session).getString("1"));
            // Exercise the Android JSON/word alignment path with real detected intervals.
            JSONObject turn=VoiceData.read(VoiceData.timeline(first)).getJSONArray("turns").getJSONObject(0);
            double start=turn.getLong("start")/1000.0+.1;
            JSONObject response=new JSONObject().put("text","Parola di prova").put("words",new JSONArray().put(new JSONObject().put("word","Parola di prova").put("start",start).put("end",start+.3)));
            VoiceData.align(first,response);
            assertEquals(turn.getInt("speaker"),VoiceData.read(VoiceData.utterances(first)).getJSONArray("groups").getJSONObject(0).getInt("speaker"));
        }finally{File[] files=session.listFiles();if(files!=null)for(File f:files)f.delete();session.delete();}
    }
}
