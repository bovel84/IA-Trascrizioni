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
        List<VoiceTimeline.Turn> turns=new ArrayList<>();JSONObject stored=read(timeline(wav));JSONArray list=stored.optJSONArray("turns");
        if(list!=null)for(int i=0;i<list.length();i++){JSONObject t=list.getJSONObject(i);turns.add(new VoiceTimeline.Turn(t.getLong("start"),t.getLong("end"),t.getInt("speaker"),t.optDouble("similarity",-1),t.optBoolean("review",true)));}
        List<VoiceTimeline.Word> words=new ArrayList<>();JSONArray raw=transcription.optJSONArray("words");
        if(raw!=null)for(int i=0;i<raw.length();i++){JSONObject w=raw.getJSONObject(i);double start=w.optDouble("start",Double.NaN),end=w.optDouble("end",Double.NaN);
            if(!Double.isFinite(start)||!Double.isFinite(end)){words.clear();break;}
            words.add(new VoiceTimeline.Word(Math.round(start*1000),Math.round(end*1000),w.optString("word","")));}
        long duration=Math.max(1,(wav.length()-44)*1000/WavFile.BYTES_PER_SECOND);JSONArray groups=new JSONArray();
        for(VoiceTimeline.Group group:new VoiceTimeline(turns).align(words,transcription.optString("text",""),duration))groups.put(new JSONObject().put("start",group.start).put("end",group.end).put("speaker",group.speaker).put("review",group.review).put("similarity",group.similarity).put("text",group.text));
        write(utterances(wav),new JSONObject().put("model",MODEL).put("groups",groups));
    }
}
