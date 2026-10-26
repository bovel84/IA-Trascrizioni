package it.voicescribe.recorder;

import java.util.*;

/** Aligns timestamped words to audio turns. Overlap, gaps and ambiguity stay unassigned. */
final class VoiceTimeline {
    static final class Turn {
        final long start,end; final int speaker; final double similarity; final boolean review;
        Turn(long start,long end,int speaker,double similarity,boolean review) {
            if(start<0||end<=start||speaker<0||speaker>32)throw new IllegalArgumentException("Intervallo vocale non valido");
            this.start=start;this.end=end;this.speaker=speaker;this.similarity=similarity;this.review=review;
        }
    }
    static final class Word {
        final long start,end;final String text;
        Word(long start,long end,String text){this.start=start;this.end=end;this.text=text;}
    }
    static final class Group {
        long start,end;final int speaker;final boolean review;double similarity;String text;
        Group(Word word,VoiceProfiles.Match match){start=word.start;end=word.end;text=word.text;speaker=match.id;review=match.review;similarity=match.similarity;}
    }
    final List<Turn> turns;
    VoiceTimeline(List<Turn> turns){this.turns=new ArrayList<>(turns);this.turns.sort(Comparator.comparingLong(t->t.start));}
    VoiceProfiles.Match at(long start,long end) {
        if(start<0||end<=start)return new VoiceProfiles.Match(0,-1,true);
        Map<Integer,Long> coverage=new HashMap<>();Map<Integer,Boolean> review=new HashMap<>();Map<Integer,Double> score=new HashMap<>();
        for(Turn t:turns){long n=Math.max(0,Math.min(end,t.end)-Math.max(start,t.start));if(n==0)continue;
            coverage.put(t.speaker,coverage.getOrDefault(t.speaker,0L)+n);review.put(t.speaker,review.getOrDefault(t.speaker,false)||t.review);score.put(t.speaker,t.similarity);}
        long duration=end-start,first=0,second=0;int winner=0;
        for(Map.Entry<Integer,Long> entry:coverage.entrySet())if(entry.getKey()!=0){long n=entry.getValue();if(n>first){second=first;first=n;winner=entry.getKey();}else second=Math.max(second,n);}
        if(first<duration*.55||second>duration*.25||coverage.getOrDefault(0,0L)>duration*.25)return new VoiceProfiles.Match(0,-1,true);
        return new VoiceProfiles.Match(winner,score.getOrDefault(winner,-1.0),review.getOrDefault(winner,true));
    }
    List<Group> align(List<Word> words,String fallback,long duration) {
        List<Group> groups=new ArrayList<>();long previous=-1;
        String original=fallback==null?"":fallback;
        StringBuilder recognized=new StringBuilder();for(Word word:words)if(word.text!=null)recognized.append(word.text);
        if(!key(recognized.toString()).equals(key(original)))return fallback(original,duration);
        List<Integer> positions=new ArrayList<>();for(int i=0;i<original.length();i++)if(Character.isLetterOrDigit(original.charAt(i)))positions.add(i);
        int consumed=0,cursor=0;
        for(Word raw:words){
            int letters=key(raw.text==null?"":raw.text).length();if(letters==0)continue;
            consumed+=letters;int end=consumed<positions.size()?positions.get(consumed):original.length();
            Word word=new Word(raw.start,raw.end,original.substring(cursor,end));cursor=end;
            if(word.text==null||word.text.trim().isEmpty())continue;
            if(word.start<0||word.end<=word.start||word.end>duration+500||word.start<previous) return fallback(fallback,duration);
            previous=word.start;VoiceProfiles.Match match=at(word.start,word.end);
            Group last=groups.isEmpty()?null:groups.get(groups.size()-1);
            if(last!=null&&last.speaker==match.id&&last.review==match.review&&word.start-last.end<=1200){last.text+=word.text;last.end=word.end;last.similarity=Math.min(last.similarity,match.similarity);}
            else groups.add(new Group(word,match));
        }
        for(Group group:groups)group.text=group.text.trim();
        return groups.isEmpty()?fallback(original,duration):groups;
    }
    private static String key(String text){StringBuilder out=new StringBuilder();for(char c:text.toCharArray())if(Character.isLetterOrDigit(c))out.append(Character.toLowerCase(c));return out.toString();}
    private static List<Group> fallback(String text,long duration){
        if(text==null||text.trim().isEmpty())return new ArrayList<>();
        return new ArrayList<>(Collections.singletonList(new Group(new Word(0,Math.max(1,duration),text),new VoiceProfiles.Match(0,-1,true))));
    }
}
