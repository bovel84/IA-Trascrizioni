package it.voicescribe.recorder;

/** The same offline modules are served by the browser and the packaged WebView. */
final class AppAssets {
    static boolean allowed(String path) {
        return path != null && path.matches("/assets/(index\\.html|native-bridge\\.js|concept-map\\.js|appearance\\.js|audio-import\\.js|icon\\.svg|manifest\\.webmanifest|vendor/(jspdf\\.umd\\.min\\.js|chart\\.umd\\.js))");
    }
}
