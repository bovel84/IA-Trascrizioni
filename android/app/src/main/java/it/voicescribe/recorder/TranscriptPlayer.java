package it.voicescribe.recorder;

import android.media.*;
import android.os.*;
import java.io.*;
import java.util.function.Consumer;

/** Foreground review of an original WAV excerpt; never modifies or re-transcribes audio. */
final class TranscriptPlayer implements AutoCloseable {
    private final File root;private final Consumer<String> error;
    private final Handler timer=new Handler(Looper.getMainLooper());private MediaPlayer current;private volatile boolean playing;
    TranscriptPlayer(File root,Consumer<String> error){this.root=root;this.error=error;}
    void play(String token,long start,long end)throws IOException{
        AudioExcerpt range=AudioExcerpt.resolve(root,token,start,end);close();MediaPlayer player=new MediaPlayer();current=player;
        try{
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(range.file.getPath());
            player.setOnErrorListener((p,what,extra)->{if(current==p){close();error.accept("Riascolto non disponibile per questo segmento");}return true;});
            player.setOnCompletionListener(p->{if(current==p)close();});
            player.setOnPreparedListener(p->{if(current!=p)return;if(range.start>0){p.setOnSeekCompleteListener(q->start(q,range));p.seekTo(range.start,MediaPlayer.SEEK_CLOSEST);}else start(p,range);});
            player.prepareAsync();
        }catch(Exception e){close();throw new IOException("Riascolto non disponibile",e);}
    }
    boolean isPlaying(){return playing;}
    private void start(MediaPlayer player,AudioExcerpt range){if(current!=player)return;try{player.start();playing=true;timer.postDelayed(()->{if(current==player)close();},range.end-range.start);}catch(Exception e){close();error.accept("Impossibile avviare il riascolto");}}
    public void close(){playing=false;timer.removeCallbacksAndMessages(null);MediaPlayer player=current;current=null;if(player!=null)try{player.release();}catch(Exception ignored){}}
}
