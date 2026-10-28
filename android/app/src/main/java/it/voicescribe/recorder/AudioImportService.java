package it.voicescribe.recorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.database.Cursor;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.*;

/** A user-started foreground import: source and short upload files survive app/process interruption. */
public final class AudioImportService extends Service {
    static final String CANCEL="it.voicescribe.CANCEL_IMPORT";
    static volatile boolean active, ready;
    static volatile String sessionId="",status="",phase="";
    static volatile int percent;
    private Thread job;
    private PowerManager.WakeLock cpu;
    private long notified;
    public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("imports","Importazione e trascrizione audio",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel=PendingIntent.getService(this,3,new Intent(this,AudioImportService.class).setAction(CANCEL),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"imports").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("VoiceScribe · Importazione audio").setContentText(status).setContentIntent(open).setOngoing(true).setProgress(100,percent,"copy".equals(phase)).addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),"Interrompi",cancel).build()).build();
    }
    private void progress(String step,String text,int value){phase=step;status=text;percent=value;long now=SystemClock.elapsedRealtime();if(now-notified>700){notified=now;getSystemService(NotificationManager.class).notify(2,notification());}}
    public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(CANCEL.equals(intent.getAction())){if(job!=null){status="Interruzione richiesta · termino la parte in corso";phase="stopping";job.interrupt();}else stopSelf();return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        if(RecordingService.active){stopSelf(startId);return START_NOT_STICKY;}
        try{
            active=true;ready=false;sessionId="";phase="copy";percent=0;status="Salvataggio della sorgente audio…";
            if(Build.VERSION.SDK_INT>=29)startForeground(2,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(2,notification());
            cpu=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VoiceScribe:Import");cpu.acquire();
            job=new Thread(()->runImport(intent),"voicescribe-import");job.start();
        }catch(Exception e){active=false;status="Importazione non avviata: "+e.getMessage();cleanup();}
        return START_NOT_STICKY;
    }
    private void runImport(Intent intent){
        NativeVoiceEngine engine=null;
        try{
            String key=intent.getStringExtra("key"),model=intent.getStringExtra("model"),language=intent.getStringExtra("language");
            if(key==null||key.trim().isEmpty())throw new IOException("Chiave Groq mancante");
            String resume=intent.getStringExtra("resume");File dir;
            if(resume!=null){if(!resume.matches("session-[0-9]{13}"))throw new IOException("Sessione non valida");dir=new File(getFilesDir(),resume);if(!dir.isDirectory())throw new IOException("Sessione non trovata");sessionId=resume;}
            else{
                sessionId="session-"+System.currentTimeMillis();dir=new File(getFilesDir(),sessionId);if(!dir.mkdir())throw new IOException("Impossibile salvare la sorgente");
                boolean voices=getSharedPreferences("native",0).getBoolean("localVoices",true);
                VoiceData.write(new File(dir,"native-voices.json"),new JSONObject().put("enabled",voices).put("model",VoiceData.MODEL));
                VoiceData.write(new File(dir,"transcription-settings.json"),new JSONObject().put("glossary",SpeechContext.glossary(getSharedPreferences("native",0).getString("glossary",""))).put("model",model).put("language",language));
                VoiceData.write(new File(dir,"import.json"),new JSONObject().put("decoded",false).put("sourceComplete",false));
                Uri uri=intent.getData();if(uri==null)throw new IOException("Seleziona il file audio");
                File partial=new File(dir,"source.copy");
                try(InputStream input=getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(partial)){
                    if(input==null)throw new IOException("File audio non leggibile");byte[] buffer=new byte[65536];int n;long copied=0;
                    while((n=input.read(buffer))!=-1){AudioImporter.check();if(dir.getUsableSpace()<32*1024*1024)throw new IOException("Spazio sul dispositivo insufficiente");output.write(buffer,0,n);copied+=n;progress("copy","Sorgente audio · "+(copied/1048576)+" MB salvati",0);}output.getFD().sync();
                }
                if(!partial.renameTo(new File(dir,"source.audio")))throw new IOException("Sorgente non salvata");
                String name="audio_originale",mime=getContentResolver().getType(uri);
                try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(Exception ignored){}
                VoiceData.write(new File(dir,"import.json"),new JSONObject().put("decoded",false).put("sourceComplete",true).put("sourceName",name==null?"audio_originale":name).put("sourceMime",mime==null?"application/octet-stream":mime));
            }
            JSONObject metadata=VoiceData.read(new File(dir,"import.json"));
            if(!metadata.optBoolean("decoded")){
                if(!metadata.optBoolean("sourceComplete"))throw new IOException("Copia incompleta: seleziona nuovamente il file originale");
                progress("decode","Preparazione dell’audio e suddivisione automatica…",0);
                AudioImporter.decode(new File(dir,"source.audio"),dir,fraction->{AudioImporter.check();progress("decode","Preparazione audio · "+Math.round(fraction*100)+"%",(int)Math.round(fraction*20));});
                VoiceData.write(new File(dir,"import.json"),metadata.put("decoded",true));
            }
            ready=true;
            File[] files=Transcriber.segments(dir);boolean quota=false;int pending=0;String failure="";
            for(int i=0;i<files.length;i++){
                AudioImporter.check();File wav=files[i];progress("transcribe","Trascrizione · parte "+(i+1)+" di "+files.length,20+(i*80/Math.max(1,files.length)));
                if(quota&&!Transcriber.hasCachedResponse(wav)&&!Transcriber.result(wav).exists()){pending++;continue;}
                try{
                    if(VoiceData.enabled(dir)&&!VoiceData.timeline(wav).exists()){
                        progress("voices","Riconoscimento voci · parte "+(i+1)+" di "+files.length,percent);
                        try{if(engine==null)engine=new NativeVoiceEngine(this,dir);engine.analyse(wav);}catch(Exception|LinkageError e){try{NativeVoiceEngine.unavailable(wav,"Voci da verificare: "+e.getMessage());}catch(Exception ignored){}}
                    }
                    progress("transcribe","Trascrizione · parte "+(i+1)+" di "+files.length,percent);
                    Transcriber.transcribe(wav,key,model,language);
                }catch(Exception e){if(Thread.currentThread().isInterrupted())throw e;pending++;failure=e.getMessage();if(e instanceof Transcriber.QuotaLimited)quota=true;}
            }
            getSharedPreferences("native",0).edit().putString("latest",sessionId).apply();
            boolean spoken=false;for(File file:files)if(Transcriber.result(file).isFile()&&Transcriber.result(file).length()>0){spoken=true;break;}
            status=pending==0?(spoken?"Audio importato e trascritto":"Audio importato · nessun parlato riconosciuto"):"Audio conservato · "+pending+" parti in attesa · "+failure;phase=pending==0?"done":"pending";percent=pending==0?100:percent;
        }catch(Exception e){boolean complete=!sessionId.isEmpty()&&new File(new File(getFilesDir(),sessionId),"source.audio").isFile();status=(complete?"Audio conservato · ":"Importazione non completata: riseleziona l’originale · ")+(e.getMessage()==null?"Importazione interrotta":e.getMessage());phase="error";}
        finally{if(engine!=null)engine.close();if(ready)getSharedPreferences("native",0).edit().putString("latest",sessionId).apply();active=false;new Handler(getMainLooper()).post(this::cleanup);}
    }
    private void cleanup(){if(cpu!=null&&cpu.isHeld())cpu.release();cpu=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    public void onTimeout(int startId,int fgsType){if(job!=null)job.interrupt();status="Importazione interrotta dal sistema · sorgente conservata";stopSelf();}
    public void onDestroy(){if(job!=null)job.interrupt();if(cpu!=null&&cpu.isHeld())cpu.release();cpu=null;super.onDestroy();}
    public IBinder onBind(Intent intent){return null;}
}
