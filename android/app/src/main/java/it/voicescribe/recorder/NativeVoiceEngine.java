package it.voicescribe.recorder;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Native ONNX segmentation and embeddings, confined to one recording/session. */
final class NativeVoiceEngine implements AutoCloseable {
    private final File session;
    private final VoiceProfiles registry=new VoiceProfiles();
    private OfflineSpeakerDiarization diarizer;
    private SpeakerEmbeddingExtractor extractor;
    NativeVoiceEngine(Context context,File session)throws Exception{
        this.session=session;
        OfflineSpeakerDiarizationConfig config=new OfflineSpeakerDiarizationConfig();
        config.getSegmentation().getPyannote().setModel("voices/segmentation.onnx");
        config.getSegmentation().getPyannote().setWindowShiftRatio(.25f);
        config.getSegmentation().setNumThreads(2);
        SpeakerEmbeddingExtractorConfig embedding=new SpeakerEmbeddingExtractorConfig("voices/embedding.onnx",2,false,"cpu");
        config.setEmbedding(embedding);config.getClustering().setNumClusters(-1);config.getClustering().setThreshold(.5f);
        config.getClustering().setComputeConfidence(true);config.setMinDurationOn(.25f);config.setMinDurationOff(.4f);
        try{
            diarizer=new OfflineSpeakerDiarization(context.getAssets(),config);
            extractor=new SpeakerEmbeddingExtractor(context.getAssets(),embedding);
            if(diarizer.sampleRate()!=WavFile.RATE)throw new IOException("Frequenza del modello vocale non compatibile");
            File saved=new File(session,"voice-profiles.json");
            if(saved.exists()){
                JSONObject data=VoiceData.read(saved);
                if(!VoiceData.MODEL.equals(data.optString("model")))throw new IOException("Modello dei profili vocali non compatibile");
                JSONArray profiles=data.getJSONArray("profiles");
                for(int i=0;i<profiles.length();i++){
                    JSONObject profile=profiles.getJSONObject(i);JSONArray raw=profile.getJSONArray("vector");
                    float[] vector=new float[raw.length()];for(int j=0;j<vector.length;j++)vector[j]=(float)raw.getDouble(j);
                    registry.restore(new VoiceProfiles.Profile(profile.getInt("id"),vector,profile.getInt("observations")));
                }
            }
        }catch(Exception|LinkageError e){close();throw e;}
    }
    int voices(){return registry.profiles.size();}
    void analyse(File wav)throws Exception{
        if(VoiceData.timeline(wav).exists())return;
        if(wav.length()<44 || wav.length()>WavFile.BYTES_PER_SECOND*60L+44)throw new IOException("Segmento vocale troppo lungo o incompleto");
        float[] samples=new float[(int)((wav.length()-44)/2)];
        byte[] pcm=new byte[samples.length*2];
        try(RandomAccessFile input=new RandomAccessFile(wav,"r")){input.seek(44);input.readFully(pcm);}
        for(int i=0;i<samples.length;i++)samples[i]=(short)((pcm[2*i]&255)|(pcm[2*i+1]<<8))/32768f;
        if(!WavFile.hasSignal(wav)){VoiceData.write(VoiceData.timeline(wav),new JSONObject().put("model",VoiceData.MODEL).put("turns",new JSONArray()));return;}
        OfflineSpeakerDiarizationSegment[] segments=diarizer.process(samples);
        Arrays.sort(segments,Comparator.comparingDouble(OfflineSpeakerDiarizationSegment::getStart));
        int[] owners=new int[samples.length];Arrays.fill(owners,-1);Set<Integer> ids=new LinkedHashSet<>();
        for(OfflineSpeakerDiarizationSegment segment:segments){
            if(!Float.isFinite(segment.getStart())||!Float.isFinite(segment.getEnd())||segment.getEnd()<=segment.getStart())continue;
            ids.add(segment.getSpeaker());int start=Math.max(0,Math.round(segment.getStart()*WavFile.RATE)),end=Math.min(samples.length,Math.round(segment.getEnd()*WavFile.RATE));
            for(int i=start;i<end;i++)owners[i]=owners[i]==-1||owners[i]==segment.getSpeaker()?segment.getSpeaker():-2;
        }
        Map<Integer,float[]> embeddings=new LinkedHashMap<>();
        for(int id:ids){
            float[] clean=new float[Math.min(samples.length,WavFile.RATE*12)];int count=0;
            for(int i=0;i<owners.length&&count<clean.length;i++)if(owners[i]==id)clean[count++]=samples[i];
            // Brief or overlapped speech is not a reliable voice sample.
            if(count<WavFile.RATE*1.5)continue;
            OnlineStream stream=extractor.createStream();
            try{stream.acceptWaveform(Arrays.copyOf(clean,count),WavFile.RATE);stream.inputFinished();if(extractor.isReady(stream))embeddings.put(id,extractor.compute(stream));}
            finally{stream.release();}
        }
        Map<Integer,VoiceProfiles.Match> assignments=registry.assign(embeddings);JSONArray turns=new JSONArray();
        for(OfflineSpeakerDiarizationSegment segment:segments){
            long start=Math.max(0,Math.round(segment.getStart()*1000)),end=Math.min(samples.length*1000L/WavFile.RATE,Math.round(segment.getEnd()*1000));
            if(end<=start)continue;
            VoiceProfiles.Match match=assignments.getOrDefault(segment.getSpeaker(),new VoiceProfiles.Match(0,-1,true));
            turns.put(new JSONObject().put("start",start).put("end",end).put("speaker",match.id).put("similarity",match.similarity).put("review",match.review));
        }
        JSONArray profiles=new JSONArray();
        for(VoiceProfiles.Profile profile:registry.profiles){JSONArray vector=new JSONArray();for(float value:profile.centroid)vector.put(value);
            profiles.put(new JSONObject().put("id",profile.id).put("vector",vector).put("observations",profile.observations));}
        VoiceData.write(new File(session,"voice-profiles.json"),new JSONObject().put("model",VoiceData.MODEL).put("profiles",profiles));
        VoiceData.write(VoiceData.timeline(wav),new JSONObject().put("model",VoiceData.MODEL).put("turns",turns));
    }
    public void close(){if(extractor!=null){extractor.release();extractor=null;}if(diarizer!=null){diarizer.release();diarizer=null;}}
    static void unavailable(File wav,String message)throws Exception{
        VoiceData.write(VoiceData.timeline(wav),new JSONObject().put("model",VoiceData.MODEL).put("turns",new JSONArray()).put("error",message));
    }
}
