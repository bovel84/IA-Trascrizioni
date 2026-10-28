package it.voicescribe.recorder;

import android.app.Instrumentation;
import android.content.Intent;
import android.webkit.WebView;
import android.view.ViewGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Runs the actual packaged HTML through MainActivity's asset router, CSP and Android WebView. */
@RunWith(AndroidJUnit4.class)
public class PackagedWorkspaceTest {
    private MainActivity activity;private WebView web;private Instrumentation instrument;
    private String js(String code)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();instrument.runOnMainSync(()->web.evaluateJavascript(code,value->{result.set(value);done.countDown();}));assertTrue(done.await(10,TimeUnit.SECONDS));return result.get();}
    @Before public void start()throws Exception{
        instrument=InstrumentationRegistry.getInstrumentation();activity=(MainActivity)instrument.startActivitySync(new Intent(instrument.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrument.runOnMainSync(()->web=(WebView)((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0));
        long limit=System.currentTimeMillis()+30000;while(!"true".equals(js("!!(window.nativeRecorderReady&&window.VoiceConceptMap&&window.VoiceAudioImport&&window.initAppearance)"))&&System.currentTimeMillis()<limit)Thread.sleep(100);
        assertEquals("Offline JavaScript blocked by native routing/CSP","true",js("!!(window.nativeRecorderReady&&window.VoiceConceptMap&&window.VoiceAudioImport&&window.initAppearance)"));
    }
    @After public void close(){if(activity!=null)instrument.runOnMainSync(activity::finish);}
    @Test public void conceptMapReallyRendersInsideThePackagedApp()throws Exception{
        assertEquals("true",js("(()=>{showPanel('ai');renderConceptMap({topic:'Progetto di prova',root:'root',nodes:[{id:'root',label:'Progetto di prova'},{id:'a',label:'Attività da pianificare'},{id:'b',label:'Risultati attesi'}],links:[{from:'root',to:'a',label:'comprende'},{from:'root',to:'b',label:'produce'}]});return $('conceptMap').querySelectorAll('[data-node]').length===3&&!$('mapViewport').hidden&&!!$('conceptMap').getAttribute('viewBox');})()"));
        assertEquals("true",js("(()=>{const view=$('mapViewport').getBoundingClientRect();return view.width>0&&typeof VoiceConceptMap.create==='function'&&conceptViewer.graph.nodes.length===3;})()"));
    }
    @Test public void appearanceAndBusyIndicatorWorkInTheNativeShell()throws Exception{
        assertEquals("true",js("(()=>{$('themeSelect').value='light';$('themeSelect').onchange({target:$('themeSelect')});setWorkTask('import','Preparazione audio · 3 di 8','Sorgente conservata',35);return document.documentElement.dataset.theme==='light'&&!$('workIndicator').hidden&&$('workProgress').value===35;})()"));
        assertEquals("true",js("(()=>{$('themeToggle').click();clearWorkTask('import');return document.documentElement.dataset.theme==='dark'&&$('workIndicator').hidden;})()"));
    }
}
