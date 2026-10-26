package it.voicescribe.recorder;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class NativeVoicesTest {
    private static Map<Integer,float[]> vectors(Object... values){Map<Integer,float[]> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((Integer)values[i],(float[])values[i+1]);return result;}
    @Test public void sameVoiceKeepsIdentityAcrossDifferentChunkClusterIds(){
        VoiceProfiles profiles=new VoiceProfiles();
        Map<Integer,VoiceProfiles.Match> first=profiles.assign(vectors(0,new float[]{1,0,0},1,new float[]{0,1,0}));
        Map<Integer,VoiceProfiles.Match> next=profiles.assign(vectors(7,new float[]{0,1,0},4,new float[]{1,0,0}));
        assertEquals(first.get(0).id,next.get(4).id);assertEquals(first.get(1).id,next.get(7).id);assertEquals(2,profiles.profiles.size());
    }
    @Test public void ambiguousEmbeddingDoesNotInventAnIdentity(){
        VoiceProfiles profiles=new VoiceProfiles();profiles.assign(vectors(0,new float[]{1,0,0},1,new float[]{0,1,0}));
        VoiceProfiles.Match match=profiles.assign(vectors(2,new float[]{.71f,.71f,0})).get(2);
        assertEquals(0,match.id);assertTrue(match.review);assertEquals(2,profiles.profiles.size());
    }
    @Test public void twoConcurrentClustersCannotClaimSameKnownPerson(){
        VoiceProfiles profiles=new VoiceProfiles();profiles.assign(vectors(0,new float[]{1,0,0}));
        Map<Integer,VoiceProfiles.Match> matches=profiles.assign(vectors(8,new float[]{1,0,0},9,new float[]{1,.01f,0}));
        assertEquals(1,matches.values().stream().filter(m->m.id>0).count());
    }
    @Test public void profilesRestoreWithoutRenumbering(){
        VoiceProfiles restored=new VoiceProfiles();restored.restore(new VoiceProfiles.Profile(7,new float[]{1,0},3));
        assertEquals(7,restored.assign(vectors(1,new float[]{1,0})).get(1).id);
    }
    @Test public void invalidEmbeddingsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->VoiceProfiles.unit(new float[]{0,0}));
        assertThrows(IllegalArgumentException.class,()->VoiceProfiles.unit(new float[]{Float.NaN,1}));
        assertThrows(IllegalArgumentException.class,()->VoiceProfiles.cosine(new float[]{1,0},new float[]{1,0,0}));
    }
    private VoiceTimeline timeline(){return new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,2000,1,.9,false),new VoiceTimeline.Turn(2000,4000,2,.9,false)));}
    @Test public void wordTimesSelectActualVoiceRatherThanSelectedChip(){
        assertEquals(1,timeline().at(100,500).id);assertEquals(2,timeline().at(2200,2500).id);
    }
    @Test public void speakerChangeInsideWordRemainsUnassigned(){assertEquals(0,timeline().at(1800,2200).id);}
    @Test public void overlappingSpeechRemainsUnassigned(){
        VoiceTimeline overlap=new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,3000,1,.9,false),new VoiceTimeline.Turn(1000,2000,2,.9,false)));
        assertEquals(0,overlap.at(1200,1500).id);
    }
    @Test public void missingSpeechIsNotAssignedToLastSpeaker(){assertEquals(0,timeline().at(5000,5500).id);}
    @Test public void alignmentSplitsWhenPeopleChangeAndPreservesAllWords(){
        List<VoiceTimeline.Group> groups=timeline().align(Arrays.asList(new VoiceTimeline.Word(100,400,"Prima"),new VoiceTimeline.Word(500,800,"frase."),new VoiceTimeline.Word(2100,2400,"Seconda")),"originale",4000);
        assertEquals(2,groups.size());assertEquals("Prima frase.",groups.get(0).text);assertEquals(2,groups.get(1).speaker);assertEquals(2400,groups.get(1).end);
    }
    @Test public void unavailableWordTimestampsPreserveTextWithoutGuessing(){
        List<VoiceTimeline.Group> groups=timeline().align(Collections.emptyList(),"Testo completo.",4000);
        assertEquals("Testo completo.",groups.get(0).text);assertEquals(0,groups.get(0).speaker);assertTrue(groups.get(0).review);
    }
    @Test public void malformedWordTimestampsPreserveOriginalText(){
        List<VoiceTimeline.Group> groups=timeline().align(Collections.singletonList(new VoiceTimeline.Word(-1,500,"Parziale")),"Originale intero.",4000);
        assertEquals("Originale intero.",groups.get(0).text);assertEquals(0,groups.get(0).speaker);
    }
}
