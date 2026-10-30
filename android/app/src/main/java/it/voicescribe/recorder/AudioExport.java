package it.voicescribe.recorder;

import java.io.*;

/** Disk-to-document copying. No whole-recording byte[] and no successful empty exports. */
final class AudioExport {
    interface Destination { OutputStream open() throws IOException; }

    static long size(File source) throws IOException {
        if (!source.isFile() || source.length() == 0) throw new IOException("File audio assente o vuoto: conserva la registrazione nell’app");
        return source.length();
    }

    static long copy(File source, long expected, Destination destination) throws IOException {
        if (size(source) != expected) throw new IOException("Il file sorgente è cambiato: ripeti l’esportazione");
        long copied = 0;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source)); OutputStream output = destination.open()) {
            if (output == null) throw new IOException("La destinazione non è disponibile: scegli un’altra cartella");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); copied += count; }
            output.flush();
            if (copied != expected || source.length() != expected) throw new IOException("Copia incompleta: il file originale è conservato");
        }
        // Returning after close also catches document providers that fail while closing.
        return copied;
    }

    static File combine(File session, File directory) throws IOException {
        File[] parts = Transcriber.segments(session); long pcm = 0;
        for (File part : parts) {
            if (part.length() < 44 || (part.length() - 44) % 2 != 0) throw new IOException("Segmento audio incompleto: usa Riprova audio non trascritto");
            pcm += part.length() - 44;
        }
        if (pcm == 0) throw new IOException("Nessun campione audio salvato in questa registrazione");
        if (pcm > 0xffffffffL - 36) throw new IOException("Audio troppo grande per un unico WAV");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Spazio di salvataggio non disponibile");
        File output = File.createTempFile("audio-", ".wav", directory);
        try {
            try (WavFile wav = new WavFile(output)) {
                byte[] buffer = new byte[65536];
                for (File part : parts) try (FileInputStream input = new FileInputStream(part)) {
                    if (input.skip(44) != 44) throw new IOException("Intestazione audio incompleta");
                    int count; while ((count = input.read(buffer)) != -1) wav.write(buffer, count);
                }
            }
            if (output.length() != pcm + 44) throw new IOException("Preparazione audio incompleta: originali conservati");
            return output;
        } catch (IOException e) { output.delete(); throw e; }
    }
}
