package it.voicescribe.recorder;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class VoiceData {
    static final String MODEL="pyannote-int8-wespeaker-resnet34-lm-v1";
    static File timeline(File wav){return new File(wav.getPath()+".voices.json");}
    static File utterances(File wav){return new File(wav.getPath()+".utterances.json");}
    static File response(File wav){return new File(wav.getPath()+".stt.json");}
    static JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}
    static void write(File file,JSONObject data)throws IOException{
        File temp=new File(file.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    static boolean enabled(File session){try{return read(new File(session,"native-voices.json")).optBoolean("enabled",false);}catch(Exception e){return false;}}
    static JSONObject names(File session){try{return read(new File(session,"voice-names.json"));}catch(Exception e){return new JSONObject();}}
    static synchronized void rename(File session,int id,String name)throws Exception{
        JSONObject labels=names(session);labels.put(String.valueOf(id),name);write(new File(session,"voice-names.json"),labels);
    }
    static void align(File wav,JSONObject transcription)throws Exception{
        List<VoiceTimeline.Turn> turns=new ArrayList<>();
        try{
            JSONArray list=read(timeline(wav)).getJSONArray("turns");
            for(int i=0;i<list.length();i++){JSONObject t=list.getJSONObject(i);turns.add(new VoiceTimeline.Turn(t.getLong("start"),t.getLong("end"),t.getInt("speaker"),t.optDouble("similarity",-1),t.optBoolean("review",true)));}
        }catch(Exception invalid){turns.clear();} // Broken voice metadata must never suppress the transcript.
        List<VoiceTimeline.Word> words=new ArrayList<>();
        try{
            JSONArray raw=transcription.optJSONArray("words");
            if(raw!=null)for(int i=0;i<raw.length();i++){JSONObject w=raw.getJSONObject(i);double start=w.optDouble("start",Double.NaN),end=w.optDouble("end",Double.NaN);
                if(!Double.isFinite(start)||!Double.isFinite(end))throw new JSONException("Tempi delle parole non validi");
                words.add(new VoiceTimeline.Word(Math.round(start*1000),Math.round(end*1000),w.getString("word")));}
        }catch(Exception invalid){words.clear();}
        long duration=duration(wav);JSONArray groups=new JSONArray();
        for(VoiceTimeline.Group group:new VoiceTimeline(turns).align(words,transcription.optString("text",""),duration))groups.put(new JSONObject().put("start",group.start).put("end",group.end).put("speaker",group.speaker).put("review",group.review).put("similarity",group.similarity).put("text",group.text));
        write(utterances(wav),new JSONObject().put("model",MODEL).put("groups",groups));
    }
    static long duration(File wav){return Math.max(1,Math.max(0,wav.length()-44)*1000/WavFile.BYTES_PER_SECOND);}
    /** Recheck persisted attribution against the authoritative full TXT, including old caches. */
    static JSONArray completeGroups(File wav,String original)throws JSONException{
        try{
            JSONArray groups=read(utterances(wav)).getJSONArray("groups");List<String> parts=new ArrayList<>();long previous=-1;
            for(int i=0;i<groups.length();i++){
                JSONObject group=groups.getJSONObject(i);long start=group.getLong("start"),end=group.getLong("end");int speaker=group.getInt("speaker");String text=group.getString("text");
                if(text.trim().isEmpty()||start<0||start<previous||end<=start||end>duration(wav)+500||speaker<0||speaker>32)throw new JSONException("Attribuzione vocale incompleta");
                previous=start;parts.add(text);
            }
            if(groups.length()>0&&VoiceTimeline.preservesText(parts,original))return groups;
        }catch(Exception invalid){ /* Use the entire TXT, never a partial/corrupt attribution cache. */ }
        return new JSONArray().put(new JSONObject().put("start",0).put("end",duration(wav)).put("speaker",0).put("review",true).put("similarity",-1).put("text",original));
    }
}
