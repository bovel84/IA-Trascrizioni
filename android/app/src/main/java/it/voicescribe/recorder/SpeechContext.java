package it.voicescribe.recorder;

import java.nio.charset.StandardCharsets;

/** Conservative UTF-8 byte budget, below Whisper's 224-token prompt window. */
final class SpeechContext {
    static String glossary(String value){return prefix(value,160);}
    static String prefix(String value,int budget){
        String text=value==null?"":value.trim();StringBuilder out=new StringBuilder();int bytes=0;
        for(int i=0;i<text.length();){int cp=text.codePointAt(i);String part=new String(Character.toChars(cp));int n=part.getBytes(StandardCharsets.UTF_8).length;
            if(bytes+n>budget)break;out.append(part);bytes+=n;i+=Character.charCount(cp);}
        return out.toString().trim();
    }
    static String prompt(String glossary,String previous){
        String terms=glossary(glossary),tail="";int budget=220-terms.getBytes(StandardCharsets.UTF_8).length-(terms.isEmpty()?0:1);
        String[] words=(previous==null?"":previous.trim()).split("\\s+");
        for(int i=words.length-1;i>=0;i--){String next=words[i]+(tail.isEmpty()?"":" "+tail);if(next.getBytes(StandardCharsets.UTF_8).length>budget)break;tail=next;}
        return terms+(terms.isEmpty()||tail.isEmpty()?"":"\n")+tail;
    }
}
