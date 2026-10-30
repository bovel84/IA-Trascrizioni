package it.voicescribe.recorder;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Bounded, continuous downmix/resampling across decoder buffers. No samples lost at buffer boundaries. */
final class PcmResampler {
    interface Sink { void write(byte[] data, int length) throws IOException; }
    private final int rate, channels, bytes, encoding;
    private final Sink sink;
    private final byte[] frame, output = new byte[8192];
    private int pending, used;
    private long frames, outputs;
    private double previous;
    // Encodings match android.media.AudioFormat, without requiring Android in JVM tests.
    PcmResampler(int rate, int channels, int encoding, Sink sink) throws IOException {
        if (rate < 8000 || rate > 192000 || channels < 1 || channels > 8) throw new IOException("Formato PCM non supportato");
        this.rate=rate; this.channels=channels; this.encoding=encoding; this.sink=sink;
        bytes=encoding==2?2:encoding==3?1:encoding==4||encoding==22?4:encoding==21?3:0;
        if(bytes==0)throw new IOException("Codifica PCM non supportata: "+encoding);
        frame=new byte[channels*bytes];
    }
    void accept(ByteBuffer input) throws IOException {
        while(input.hasRemaining()) {
            frame[pending++]=input.get();
            if(pending!=frame.length)continue;
            pending=0;double sample=0;
            for(int c=0;c<channels;c++) {
                int at=c*bytes,value=frame[at]&255;
                if(bytes>=2)value|=frame[at+1]<<8;
                if(bytes>=3)value=(value&65535)|(frame[at+2]<<16);
                if(bytes==4)value=(value&16777215)|(frame[at+3]<<24);
                if(encoding==2)sample+=value/32768.0;
                else if(encoding==3)sample+=(value-128)/128.0;
                else if(encoding==4){float number=Float.intBitsToFloat(value);sample+=Float.isFinite(number)?Math.max(-1,Math.min(1,number)):0;}
                else if(encoding==22)sample+=value/2147483648.0;
                else sample+=value/8388608.0;
            }
            sample/=channels;
            long index=frames++;
            // Output position uses integer counters, avoiding cumulative drift on hours of audio.
            while(outputs*(long)rate <= index*(long)WavFile.RATE) {
                double position=outputs*(double)rate/WavFile.RATE;
                double fraction=index==0?1:Math.max(0,Math.min(1,position-(index-1)));
                emit(index==0?sample:previous+(sample-previous)*fraction);outputs++;
            }
            previous=sample;
        }
    }
    private void emit(double value)throws IOException {
        int pcm=(int)Math.round(Math.max(-1,Math.min(32767/32768.0,value))*32768);
        output[used++]=(byte)pcm;output[used++]=(byte)(pcm>>8);
        if(used==output.length)flush();
    }
    private void flush()throws IOException{if(used>0){sink.write(output,used);used=0;}}
    void finish()throws IOException {
        if(pending!=0)throw new IOException("Ultimo campione audio incompleto");
        long wanted=(frames*(long)WavFile.RATE+rate-1)/rate;
        while(outputs<wanted){emit(previous);outputs++;}flush();
    }
}
