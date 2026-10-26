package it.voicescribe.recorder;

import java.util.*;

/** Session-local embedding registry. Scores are cosine similarities, not probabilities. */
final class VoiceProfiles {
    static final double MATCH = .65, NEW_VOICE = .55, MARGIN = .08;
    static final int MAX_VOICES = 32;
    static final class Profile {
        final int id; float[] centroid; int observations;
        Profile(int id, float[] centroid, int observations) {
            this.id=id; this.centroid=unit(centroid); this.observations=Math.max(1,observations);
        }
    }
    static final class Match {
        final int id; final double similarity; final boolean review;
        Match(int id, double similarity, boolean review) { this.id=id;this.similarity=similarity;this.review=review; }
    }
    final List<Profile> profiles = new ArrayList<>();
    void restore(Profile profile) {
        if (profile.id<1 || profile.id>MAX_VOICES || profiles.stream().anyMatch(p->p.id==profile.id)) throw new IllegalArgumentException("Profilo vocale non valido");
        if (!profiles.isEmpty() && profile.centroid.length!=profiles.get(0).centroid.length) throw new IllegalArgumentException("Dimensione del profilo non valida");
        profiles.add(profile);
    }
    Map<Integer,Match> assign(Map<Integer,float[]> embeddings) {
        Map<Integer,Match> result=new LinkedHashMap<>(); Set<Integer> used=new HashSet<>();
        // Recognize existing voices first; a new cluster must not steal an established ID.
        List<Map.Entry<Integer,float[]>> ordered=new ArrayList<>(embeddings.entrySet());
        ordered.sort((a,b)->Double.compare(best(b.getValue()),best(a.getValue())));
        for (Map.Entry<Integer,float[]> entry:ordered) {
            float[] vector=unit(entry.getValue()); Profile winner=null; double first=-1,second=-1;
            for (Profile profile:profiles) {
                double score=cosine(vector,profile.centroid);
                if (score>first) { second=first;first=score;winner=profile; } else second=Math.max(second,score);
            }
            if (winner!=null && first>=MATCH && first-second>=MARGIN && !used.contains(winner.id)) {
                used.add(winner.id); result.put(entry.getKey(),new Match(winner.id,first,first<.75));
                // Low confidence matches never alter a stored voice embedding.
                if (first>=.75) {
                    int weight=Math.min(winner.observations,20);float[] updated=new float[vector.length];
                    for(int i=0;i<vector.length;i++) updated[i]=(winner.centroid[i]*weight+vector[i])/(weight+1);
                    winner.centroid=unit(updated);winner.observations++;
                }
            } else if (first<NEW_VOICE && profiles.size()<MAX_VOICES) {
                int id=profiles.stream().mapToInt(p->p.id).max().orElse(0)+1;
                if (id>MAX_VOICES) { result.put(entry.getKey(),new Match(0,first,true));continue; }
                profiles.add(new Profile(id,vector,1)); used.add(id); result.put(entry.getKey(),new Match(id,first,false));
            } else result.put(entry.getKey(),new Match(0,first,true));
        }
        return result;
    }
    private double best(float[] vector) { double value=-1;for(Profile p:profiles)value=Math.max(value,cosine(vector,p.centroid));return value; }
    static float[] unit(float[] vector) {
        if(vector==null || vector.length<2)throw new IllegalArgumentException("Profilo vocale vuoto");
        double norm=0;for(float v:vector){if(!Float.isFinite(v))throw new IllegalArgumentException("Profilo vocale non numerico");norm+=(double)v*v;}
        if(norm<1e-12)throw new IllegalArgumentException("Profilo vocale senza segnale");
        float[] output=new float[vector.length];norm=Math.sqrt(norm);for(int i=0;i<vector.length;i++)output[i]=(float)(vector[i]/norm);return output;
    }
    static double cosine(float[] a,float[] b) {
        if(a.length!=b.length)throw new IllegalArgumentException("Modello vocale incompatibile");
        double dot=0,aa=0,bb=0;for(int i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
        return aa>0&&bb>0?Math.max(-1,Math.min(1,dot/Math.sqrt(aa*bb))):-1;
    }
}
