package it.voicescribe.recorder;

/** Prefer a quiet boundary after 30 s, cap latency at 35 s. No sample is removed. */
final class AudioChunkPolicy {
    private double peakEnergy;private long quietBytes;
    boolean shouldClose(long segmentBytes,int count,double rms){
        peakEnergy=Math.max(peakEnergy,rms);
        quietBytes=rms<=Math.max(100,peakEnergy*.12)?quietBytes+count:0;
        return segmentBytes>=WavFile.BYTES_PER_SECOND*35L || segmentBytes>=WavFile.BYTES_PER_SECOND*30L&&quietBytes>=WavFile.BYTES_PER_SECOND*.4;
    }
    static double rms(byte[] pcm,int count){long sum=0;int samples=0;for(int i=0;i+1<count;i+=2){int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));sum+=(long)sample*sample;samples++;}return samples==0?0:Math.sqrt(sum/(double)samples);}
}
