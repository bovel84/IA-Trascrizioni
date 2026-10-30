package it.voicescribe.recorder;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Application-owned export queue: independent of Activity recreation and STT requests. */
final class DocumentExports {
    interface Listener { boolean pick(Entry entry); void changed(String message); }
    interface Preparation { Entry run() throws Exception; }
    static final class Entry {
        final File source; final String name, type; final boolean disposable; final long bytes;
        String destination = ""; boolean failed;
        Entry(File source, String name, String type, boolean disposable) throws IOException {
            this(source, name, type, disposable, AudioExport.size(source));
        }
        private Entry(File source, String name, String type, boolean disposable, long bytes) {
            this.source = source; this.name = name; this.type = type; this.disposable = disposable; this.bytes = bytes;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("source",source.getAbsolutePath()).put("name",name).put("type",type)
                .put("disposable",disposable).put("bytes",bytes).put("destination",destination).put("failed",failed);
        }
        static Entry read(JSONObject json) throws JSONException {
            Entry entry = new Entry(new File(json.getString("source")),json.getString("name"),json.getString("type"),json.getBoolean("disposable"),json.getLong("bytes"));
            entry.destination=json.optString("destination",""); entry.failed=json.optBoolean("failed"); return entry;
        }
    }

    private static DocumentExports instance;
    static synchronized DocumentExports get(Context context) {
        if (instance == null) instance = new DocumentExports(context, new File(context.getFilesDir(),"pending-exports.json"));
        return instance;
    }
    private final Context app;
    private final File journal;
    private final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r,"voicescribe-exports"));
    private final Deque<Entry> queue = new ArrayDeque<>();
    private WeakReference<Listener> listener = new WeakReference<>(null);
    private boolean pickerOpen, copying;
    private int preparing;
    private volatile String status = "";
    private volatile boolean busy, failed;

    DocumentExports(Context context, File journal) {
        app=context.getApplicationContext(); this.journal=journal;
        if (journal.isFile()) try {
            JSONObject saved=VoiceData.read(journal); status=saved.optString("status","");
            JSONArray entries=saved.getJSONArray("entries");
            for(int i=0;i<entries.length();i++) {
                Entry entry=Entry.read(entries.getJSONObject(i));
                String path=entry.source.getCanonicalPath();
                if (!path.startsWith(app.getFilesDir().getCanonicalPath()+File.separator)
                    && !path.startsWith(app.getCacheDir().getCanonicalPath()+File.separator)) throw new IOException("Sorgente non valida");
                queue.add(entry);
            }
            failed=!queue.isEmpty()&&queue.peek().failed;
        } catch(Exception e) { status="Esportazione interrotta: audio originale conservato, ripeti Scarica Audio"; }
    }
    File directory() throws IOException {
        File dir=new File(app.getFilesDir(),"exports");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Spazio di esportazione non disponibile"); return dir;
    }
    void attach(Listener value) { listener=new WeakReference<>(value); }
    void detach(Listener value) { if(listener.get()==value)listener.clear(); }
    String status() { return status; }
    boolean busy() { return busy; }
    boolean failed() { return failed; }

    void prepare(Preparation task) {
        main.post(()->{
            preparing++; busy=true; notifyStatus("Preparo il file da salvare…");
            io.execute(()->{
                Entry prepared=null; String error=null;
                try { prepared=task.run(); } catch(Exception e) { error=e.getMessage(); }
                final Entry result=prepared; final String failure=error;
                main.post(()->{
                    preparing--; busy=copying||preparing>0;
                    if(result!=null) { queue.add(result); persistAndDispatch(); }
                    else notifyStatus("Esportazione non riuscita: "+(failure==null?"riprova":failure));
                });
            });
        });
    }
    void resume() { dispatch(); }
    void result(int result, Uri destination, int flags) {
        pickerOpen=false;
        Entry entry=queue.peek();
        if(entry==null){notifyStatus("Sorgente dell’esportazione non trovata: ripeti Scarica Audio. Registrazioni conservate.");return;}
        if(result!=Activity.RESULT_OK||destination==null){queue.remove();if(entry.disposable)entry.source.delete();persistAndDispatch();return;}
        try { app.getContentResolver().takePersistableUriPermission(destination,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
        catch(RuntimeException ignored) { /* Temporary document grants still permit this export. */ }
        entry.destination=destination.toString(); entry.failed=false; failed=false; persistAndDispatch();
    }
    void retry() {
        Entry entry=queue.peek(); if(entry==null||copying)return;
        entry.failed=false; entry.destination=""; pickerOpen=false; failed=false; persistAndDispatch();
    }
    void cancel() {
        if(copying)return;Entry entry=queue.poll();if(entry!=null&&entry.disposable)entry.source.delete();
        failed=false;pickerOpen=false;persistAndDispatch();
    }
    private void persistAndDispatch() {
        try { save(); dispatch(); }
        catch(Exception e){failed=true;notifyStatus("Salvataggio dell’esportazione non riuscito: audio originale conservato");}
    }
    private void save() throws Exception {
        JSONArray entries=new JSONArray();for(Entry entry:queue)entries.put(entry.json());
        VoiceData.write(journal,new JSONObject().put("entries",entries).put("status",status));
    }
    private void dispatch() {
        Entry entry=queue.peek(); if(entry==null||copying||pickerOpen||entry.failed)return;
        if(entry.destination.isEmpty()) {
            Listener current=listener.get();
            if(current!=null)pickerOpen=current.pick(entry);
            return;
        }
        copying=true;busy=true;notifyStatus("Salvataggio del file in corso…");
        io.execute(()->{
            String error=null;long count=0;Uri target=Uri.parse(entry.destination);
            try {
                count=AudioExport.copy(entry.source,entry.bytes,()->app.getContentResolver().openOutputStream(target,"wt"));
                try(Cursor cursor=app.getContentResolver().query(target,new String[]{OpenableColumns.SIZE},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0)&&cursor.getLong(0)>=0&&cursor.getLong(0)!=count)
                        throw new IOException("La destinazione risulta vuota o incompleta: scegli un’altra cartella");
                }catch(RuntimeException unsupported){ /* A provider can omit readable metadata. Successful writes/close are still required. */ }
            }
            catch(Exception e) {
                error=e.getMessage();
                try { if(DocumentsContract.isDocumentUri(app,target))DocumentsContract.deleteDocument(app.getContentResolver(),target); } catch(Exception ignored) { }
            }
            final String failure=error;final long bytes=count;
            main.post(()->{
                copying=false;busy=preparing>0;
                if(failure==null) {
                    queue.remove();if(entry.disposable)entry.source.delete();failed=false;
                    notifyStatus("File salvato · "+bytes+" byte");
                } else {
                    entry.failed=true;failed=true;entry.destination="";
                    notifyStatus("Salvataggio non riuscito: "+failure+" · file originale conservato. Riprova salvataggio.");
                }
                persistAndDispatch();
            });
        });
    }
    private void notifyStatus(String text) {
        status=text;Listener current=listener.get();if(current!=null)current.changed(text);
    }
}
