package it.voicescribe.recorder;

import java.io.*;

/** Resolves a transcript time range to a recording owned by this app. */
final class AudioExcerpt {
    final File file;final long start,end;
    private AudioExcerpt(File file,long start,long end){this.file=file;this.start=start;this.end=end;}
    static AudioExcerpt resolve(File root,String token,long offset,long finish)throws IOException{
        if(token==null||!token.matches("session-[0-9]{13}/[0-9]{12}\\.wav(?:#[0-9]+)?"))throw new IOException("Segmento audio non valido");
        String path=token.split("#",2)[0];File file=new File(root,path).getCanonicalFile();File expected=new File(root,path.split("/")[0]).getCanonicalFile();
        if(!file.getParentFile().equals(expected)||!expected.getParentFile().equals(root.getCanonicalFile())||!file.isFile()||file.length()<=44)throw new IOException("Audio originale non disponibile");
        long base=Long.parseLong(file.getName().replace(".wav","")),duration=(file.length()-44)*1000/WavFile.BYTES_PER_SECOND;
        if(offset<base||finish<=offset||offset-base>=duration)throw new IOException("Tempo audio non valido");
        return new AudioExcerpt(file,offset-base,Math.min(duration,finish-base));
    }
}
