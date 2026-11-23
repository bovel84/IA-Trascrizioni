package it.voicescribe.recorder;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

final class ScreenVideos {
    private static final String MP4_NAME = "schermo-[0-9]+-[0-9]+\\.mp4";
    private static final String PART_NAME = "schermo-[0-9]+-[0-9]+\\.part";

    static File directory(File files) {
        return new File(files, "screen-videos");
    }

    static synchronized File createPartial(File files) throws IOException {
        File dir = directory(files);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cartella video non disponibile");
        return File.createTempFile("schermo-" + System.currentTimeMillis() + "-", ".part", dir);
    }

    static boolean hasFrames(File file) {
        if (file == null || file.length() == 0) return false;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    return extractor.getSampleTime() >= 0;
                }
            }
            return false;
        } catch (IOException | RuntimeException invalid) {
            return false;
        } finally {
            extractor.release();
        }
    }

    static synchronized void publish(File partial) throws IOException {
        String name = partial.getName();
        if (!name.matches(PART_NAME)) throw new IOException("Nome del video non valido");
        File target = new File(partial.getParentFile(), name.substring(0, name.length() - 5) + ".mp4");
        if (target.exists() || !partial.renameTo(target)) throw new IOException("Impossibile archiviare il video");
    }

    static File open(File files, String name) throws IOException {
        if (name == null || !name.matches(MP4_NAME)) throw new IOException("Video non valido");
        File file = new File(directory(files), name);
        if (!file.isFile() || file.length() == 0) throw new IOException("Video non disponibile");
        return file;
    }

    static synchronized JSONArray list(File files, boolean recover) throws JSONException {
        File dir = directory(files);
        if (recover && !ScreenRecordingService.active) {
            File[] partials = dir.listFiles((parent, name) -> name.matches(PART_NAME));
            if (partials != null) for (File file : partials) {
                if (hasFrames(file)) {
                    try { publish(file); } catch (IOException ignored) { /* Keep the original for the next attempt. */ }
                }
            }
        }
        File[] videos = dir.listFiles((parent, name) -> name.matches(MP4_NAME));
        JSONArray result = new JSONArray();
        if (videos == null) return result;
        Arrays.sort(videos, Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
        for (File video : videos) {
            if (!video.isFile() || video.length() == 0) continue;
            result.put(new JSONObject().put("name", video.getName())
                .put("date", video.lastModified()).put("bytes", video.length()));
        }
        return result;
    }

    private ScreenVideos() { }
}
