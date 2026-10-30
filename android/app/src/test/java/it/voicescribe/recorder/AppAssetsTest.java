package it.voicescribe.recorder;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppAssetsTest {
    @Test public void allOfflineModulesAreServedByTheWebView(){for(String name:new String[]{"index.html","concept-map.js","audio-import.js","appearance.js","native-bridge.js","vendor/chart.umd.js","vendor/jspdf.umd.min.js"})assertTrue(name,AppAssets.allowed("/assets/"+name));}
    @Test public void unrelatedAndTraversalPathsRemainBlocked(){for(String path:new String[]{null,"/assets/../source.audio","/assets/source.audio","/assets/concept-map.js/more","/other/index.html"})assertFalse(AppAssets.allowed(path));}
}
