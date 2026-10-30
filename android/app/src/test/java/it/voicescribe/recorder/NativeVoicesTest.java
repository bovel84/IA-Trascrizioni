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
        List<VoiceTimeline.Group> groups=timeline().align(Arrays.asList(new VoiceTimeline.Word(100,400,"Prima"),new VoiceTimeline.Word(500,800,"frase."),new VoiceTimeline.Word(2100,2400,"Seconda")),"Prima frase. Seconda",4000);
        assertEquals(2,groups.size());assertEquals("Prima frase.",groups.get(0).text);assertEquals(2,groups.get(1).speaker);assertEquals(2400,groups.get(1).end);
    }
    @Test public void incompleteWordListCannotDropTheRestOfTheTranscript(){
        List<VoiceTimeline.Group> groups=timeline().align(Collections.singletonList(new VoiceTimeline.Word(100,500,"Solo")),"Solo una parte del testo.",4000);
        assertEquals("Solo una parte del testo.",groups.get(0).text);assertEquals(0,groups.get(0).speaker);
    }
    @Test public void originalPunctuationSurvivesWordAlignment(){
        List<VoiceTimeline.Group> groups=timeline().align(Arrays.asList(new VoiceTimeline.Word(100,300,"Ciao"),new VoiceTimeline.Word(400,700,"Marco")),"Ciao, Marco!",4000);
        assertEquals("Ciao, Marco!",groups.get(0).text);assertEquals(1,groups.get(0).speaker);
    }
    @Test public void unavailableWordTimestampsPreserveTextWithoutGuessing(){
        List<VoiceTimeline.Group> groups=timeline().align(Collections.emptyList(),"Testo completo.",4000);
        assertEquals("Testo completo.",groups.get(0).text);assertEquals(0,groups.get(0).speaker);assertTrue(groups.get(0).review);
    }
    @Test public void malformedWordTimestampsPreserveOriginalText(){
        List<VoiceTimeline.Group> groups=timeline().align(Collections.singletonList(new VoiceTimeline.Word(-1,500,"Parziale")),"Originale intero.",4000);
        assertEquals("Originale intero.",groups.get(0).text);assertEquals(0,groups.get(0).speaker);
    }
    @Test public void speechOutsideDetectedTurnsIsNeverDiscarded(){
        List<VoiceTimeline.Group> groups=timeline().align(Arrays.asList(new VoiceTimeline.Word(100,300,"Prima"),new VoiceTimeline.Word(4100,4500,"anche"),new VoiceTimeline.Word(5000,5500,"dopo")),"Prima, anche dopo!",6000);
        assertEquals(2,groups.size());assertEquals(0,groups.get(1).speaker);
        assertEquals("anche dopo!",groups.get(1).text);
        assertTrue(VoiceTimeline.preservesText(Arrays.asList(groups.get(0).text,groups.get(1).text),"Prima, anche dopo!"));
    }
    @Test public void noRecognizedVoiceStillKeepsEveryWord(){
        List<VoiceTimeline.Group> groups=new VoiceTimeline(Collections.emptyList()).align(Arrays.asList(new VoiceTimeline.Word(100,300,"Sì"),new VoiceTimeline.Word(400,600,"è"),new VoiceTimeline.Word(700,900,"12,50")),"Sì, è 12,50 €!",4000);
        assertEquals("Sì, è 12,50 €!",groups.get(0).text);assertEquals(0,groups.get(0).speaker);
    }
    @Test public void incompleteWordTimesAtTheEndCannotTruncateText(){
        String text="Inizio, centro e conclusione.";
        List<VoiceTimeline.Group> groups=timeline().align(Arrays.asList(new VoiceTimeline.Word(100,300,"Inizio"),new VoiceTimeline.Word(500,700,"centro"),new VoiceTimeline.Word(2100,2300,"e"),new VoiceTimeline.Word(3500,9000,"conclusione")),text,4000);
        assertEquals(1,groups.size());assertEquals(text,groups.get(0).text);assertEquals(0,groups.get(0).speaker);
    }
    @Test public void persistedGroupsMustPreservePunctuationNumbersAndRepeatedWords(){
        assertFalse(VoiceTimeline.preservesText(Arrays.asList("È 12,50 €", "sì."),"È 12,50 €: sì, sì."));
        assertFalse(VoiceTimeline.preservesText(Arrays.asList("È 1250 €", "sì, sì."),"È 12,50 €: sì, sì."));
        assertTrue(VoiceTimeline.preservesText(Arrays.asList("È 12,50 €:","sì, sì."),"È 12,50 €: sì, sì."));
    }
    @Test public void coverageAllowsOnlyWhitespaceChanges(){
        assertTrue(VoiceTimeline.preservesText(Arrays.asList("Ciao,", "🙂 Marco!"),"Ciao,\n🙂\u00a0Marco!"));
        assertFalse(VoiceTimeline.preservesText(Arrays.asList("Ciao, Marco!"),"Ciao, 🙂 Marco!"));
        assertFalse(VoiceTimeline.preservesText(Arrays.asList("ciao,", "Marco!"),"Ciao, Marco!"));
    }
    private VoiceTimeline shortGap(int secondSpeaker,boolean review){return new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,1700,1,.9,false),new VoiceTimeline.Turn(2000,4000,secondSpeaker,.85,review)));}
    @Test public void shortQuietGapBetweenTheSameVoiceKeepsContinuityForReview(){VoiceProfiles.Match match=shortGap(1,false).at(1750,1950);assertEquals(1,match.id);assertTrue(match.review);assertEquals(.85,match.similarity,.001);}
    @Test public void aPauseBetweenDifferentPeopleCannotBorrowEitherVoice(){assertEquals(0,shortGap(2,false).at(1750,1950).id);}
    @Test public void uncertainNeighborsCannotConfirmAGap(){assertEquals(0,shortGap(1,true).at(1750,1950).id);}
    @Test public void explicitUnknownSpeechInsideAGapIsNotTreatedAsSilence(){VoiceTimeline t=new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,1700,1,.9,false),new VoiceTimeline.Turn(1720,1980,0,-1,true),new VoiceTimeline.Turn(2000,4000,1,.9,false)));assertEquals(0,t.at(1750,1950).id);}
    @Test public void anotherVoiceOverlappingTheWordBlocksContinuity(){VoiceTimeline t=new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,1700,1,.9,false),new VoiceTimeline.Turn(1500,1680,2,.9,false),new VoiceTimeline.Turn(2000,4000,1,.9,false)));assertEquals(0,t.at(1550,1950).id);}
    @Test public void longSilenceNeverBecomesRecognizedSpeech(){VoiceTimeline t=new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,1000,1,.9,false),new VoiceTimeline.Turn(3000,4000,1,.9,false)));assertEquals(0,t.at(1500,2000).id);}
    @Test public void gapAlignmentRetainsTheSmallWordAndItsTimestamps(){List<VoiceTimeline.Group> groups=shortGap(1,false).align(Arrays.asList(new VoiceTimeline.Word(1500,1650,"anche"),new VoiceTimeline.Word(1750,1950,"con"),new VoiceTimeline.Word(2100,2300,"loro")),"anche con loro",4000);assertEquals(3,groups.size());assertEquals("con",groups.get(1).text);assertEquals(1,groups.get(1).speaker);assertEquals(1750,groups.get(1).start);assertTrue(groups.get(1).review);}
    @Test public void overlappingSpeechCarriesAnExplicitReasonForTheContinuousView(){
        VoiceTimeline t=new VoiceTimeline(Arrays.asList(new VoiceTimeline.Turn(0,3000,1,.9,false),new VoiceTimeline.Turn(1000,2000,2,.9,false)));
        VoiceTimeline.Group word=t.align(Collections.singletonList(new VoiceTimeline.Word(1200,1500,"è")),"è",4000).get(0);
        assertEquals(0,word.speaker);assertEquals("overlap",word.unassignedReason);assertEquals("è",word.text);
    }
    @Test public void aWordStraddlingDifferentSpeakersIsNotJustTimestampJitter(){
        VoiceTimeline.Group word=timeline().align(Collections.singletonList(new VoiceTimeline.Word(1800,2200,"con")),"con",4000).get(0);
        assertEquals(0,word.speaker);assertEquals("speaker-change",word.unassignedReason);
    }
    @Test public void aWordOutsideDetectedSpeechIsDistinguishedFromAnUnknownVoice(){
        VoiceTimeline.Group missing=timeline().align(Collections.singletonList(new VoiceTimeline.Word(4100,4400,"è")),"è",5000).get(0);
        assertEquals("timing",missing.unassignedReason);
        VoiceTimeline t=new VoiceTimeline(Collections.singletonList(new VoiceTimeline.Turn(1000,2000,0,-1,true)));
        assertEquals("unknown-voice",t.align(Collections.singletonList(new VoiceTimeline.Word(1200,1500,"è")),"è",4000).get(0).unassignedReason);
    }
    @Test public void missingWordTimesCannotMasqueradeAsAShortUnassignedWord(){
        assertEquals("unavailable",timeline().align(Collections.emptyList(),"è",4000).get(0).unassignedReason);
    }
}
