package it.voicescribe.recorder;

import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.Locale;

/** Decodes an on-disk source incrementally. Every upload is a short, complete PCM WAV. */
final class AudioImporter {
    interface Progress { void update(double fraction) throws IOException; }
    static void decode(File source, File dir, Progress progress) throws Exception {
        // A retry before decoding completed starts cleanly; transcription only starts after completion.
        File[] stale=dir.listFiles((d,n)->n.matches("[0-9]{12}\\.(wav|part)(\\..*)?"));
        if(stale!=null)for(File file:stale)if(!file.delete())throw new IOException("Impossibile riprendere la preparazione audio");
        try(Chunks chunks=new Chunks(dir)) {
            if(!wave(source,chunks,progress))codec(source,chunks,progress);
            if(chunks.total==0)throw new IOException("Il file non contiene campioni audio");
        }
        progress.update(1);
    }
    private static boolean wave(File source,Chunks chunks,Progress progress)throws Exception {
        try(RandomAccessFile in=new RandomAccessFile(source,"r")) {
            if(in.length()<12||in.readInt()!=0x52494646)return false;
            in.skipBytes(4);if(in.readInt()!=0x57415645)return false;
            int encoding=0,channels=0,rate=0,bits=0;long offset=-1,size=0;
            while(in.getFilePointer()+8<=in.length()) {
                int tag=in.readInt();long length=Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())),start=in.getFilePointer();
                if(start+length>in.length())throw new IOException("File WAV incompleto");
                if(tag==0x666d7420&&length>=16){encoding=Short.toUnsignedInt(Short.reverseBytes(in.readShort()));channels=Short.toUnsignedInt(Short.reverseBytes(in.readShort()));rate=Integer.reverseBytes(in.readInt());in.skipBytes(6);bits=Short.toUnsignedInt(Short.reverseBytes(in.readShort()));}
                if(tag==0x64617461){offset=start;size=length;}
                in.seek(start+length+(length&1));
            }
            if(offset<0||size==0)throw new IOException("File WAV senza audio");
            int pcm=encoding==3&&bits==32?4:encoding==1?(bits==8?3:bits==16?2:bits==24?21:bits==32?22:0):0;
            if(pcm==0)return false;
            PcmResampler resampler=new PcmResampler(rate,channels,pcm,chunks::write);
            byte[] buffer=new byte[65536];long left=size;in.seek(offset);
            while(left>0){check();int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new EOFException("Audio incompleto");resampler.accept(ByteBuffer.wrap(buffer,0,n));left-=n;progress.update(1-left/(double)size);}
            resampler.finish();return true;
        }
    }
    private static void codec(File source,Chunks chunks,Progress progress)throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaCodec decoder=null;
        try {
            extractor.setDataSource(source.getAbsolutePath());MediaFormat format=null;String mime=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat candidate=extractor.getTrackFormat(i);String type=candidate.getString(MediaFormat.KEY_MIME);if(type!=null&&type.startsWith("audio/")){format=candidate;mime=type;extractor.selectTrack(i);break;}}
            if(format==null)throw new IOException("Nessuna traccia audio leggibile nel file");
            long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
            PcmResampler pcm=null;
            if("audio/raw".equals(mime)) {
                pcm=resampler(format,chunks);ByteBuffer buffer=ByteBuffer.allocate(1024*1024);
                while(true){check();buffer.clear();int n=extractor.readSampleData(buffer,0);if(n<0)break;buffer.position(0);buffer.limit(n);pcm.accept(buffer);progress.update(duration>0?Math.min(.99,extractor.getSampleTime()/(double)duration):0);extractor.advance();}pcm.finish();return;
            }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            decoder=MediaCodec.createDecoderByType(mime);decoder.configure(format,null,null,0);decoder.start();
            boolean inputDone=false,done=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long activity=SystemClock.elapsedRealtime();
            while(!done) {
                check();
                if(!inputDone){int index=decoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer input=decoder.getInputBuffer(index);if(input==null)throw new IOException("Buffer audio non disponibile");input.clear();int n=extractor.readSampleData(input,0);if(n<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(index,0,n,Math.max(0,extractor.getSampleTime()),0);extractor.advance();}activity=SystemClock.elapsedRealtime();}}
                int output=decoder.dequeueOutputBuffer(info,10000);
                if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(pcm!=null)pcm.finish();pcm=resampler(decoder.getOutputFormat(),chunks);activity=SystemClock.elapsedRealtime();}
                else if(output>=0){try{if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){if(pcm==null)pcm=resampler(decoder.getOutputFormat(),chunks);ByteBuffer data=decoder.getOutputBuffer(output);if(data==null)throw new IOException("Decodifica audio interrotta");data.position(info.offset);data.limit(info.offset+info.size);pcm.accept(data);progress.update(duration>0?Math.max(0,Math.min(.99,info.presentationTimeUs/(double)duration)):0);}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;activity=SystemClock.elapsedRealtime();}finally{decoder.releaseOutputBuffer(output,false);}}
                if(SystemClock.elapsedRealtime()-activity>30000)throw new IOException("Il decoder audio non risponde");
            }
            if(pcm!=null)pcm.finish();
        }finally{if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}extractor.release();}
    }
    private static PcmResampler resampler(MediaFormat format,Chunks chunks)throws IOException {
        return new PcmResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE),format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),format.containsKey(MediaFormat.KEY_PCM_ENCODING)?format.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT,chunks::write);
    }
    static void check()throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Importazione interrotta: audio conservato");}
    private static final class Chunks implements Closeable {
        final File dir;long total,segment;File partial;WavFile wav;AudioChunkPolicy policy=new AudioChunkPolicy();
        Chunks(File dir){this.dir=dir;}
        void write(byte[] data,int count)throws IOException {
            check();if(dir.getUsableSpace()<16*1024*1024)throw new IOException("Spazio sul dispositivo insufficiente: sorgente conservata");
            if(wav==null){partial=new File(dir,String.format(Locale.ROOT,"%012d.part",total*1000/WavFile.BYTES_PER_SECOND));wav=new WavFile(partial);segment=0;policy=new AudioChunkPolicy();}
            wav.write(data,count);total+=count;segment+=count;
            if(policy.shouldClose(segment,count,AudioChunkPolicy.rms(data,count))){finish();}
        }
        private void finish()throws IOException{if(wav==null)return;wav.close();wav=null;if(!partial.renameTo(new File(partial.getPath().replace(".part",".wav"))))throw new IOException("Salvataggio segmento importato fallito");}
        public void close()throws IOException{finish();}
    }
}
