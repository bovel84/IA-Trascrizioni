const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
const root=path.resolve(__dirname,'..'),output=process.env.PREVIEW_OUTPUT||path.join(root,'preview-output'),map=require('./fixtures/concept-map.json');fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN||undefined,args:['--no-sandbox']}),context=await browser.newContext({viewport:{width:390,height:900}}),page=await context.newPage(),errors=[];
 page.on('pageerror',error=>errors.push(error.message));page.on('dialog',dialog=>dialog.accept());let requests=0,failAt=2,delay=70,uploads=[];
 await page.route('https://**/*',async route=>{
  const url=route.request().url();if(url.includes('chart.umd'))return route.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/chart.umd.js'),contentType:'application/javascript'});
  if(url.includes('jspdf.umd'))return route.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/jspdf.umd.min.js'),contentType:'application/javascript'});
  if(url.includes('audio/transcriptions')){requests++;uploads.push(route.request().postDataBuffer().length);await new Promise(resolve=>setTimeout(resolve,delay));return route.fulfill({status:requests===failAt?429:200,contentType:'application/json',body:JSON.stringify(requests===failAt?{error:{message:'Quota test'}}:{text:'Passaggio '+requests+' completo, senza tagli.',segments:[]})});}
  return route.abort();
 });
 await page.goto('http://127.0.0.1:8088',{waitUntil:'networkidle'});
 await page.evaluate(map=>{whisperKey='gsk_test';notesText='Progetto precedente da conservare';$('notesTextarea').value=notesText;restoreAnalysisState({concept:map});showPanel('ai');},map);
 for(const theme of ['dark','light']){
  await page.evaluate(theme=>{$('themeSelect').value=theme;$('themeSelect').onchange({target:$('themeSelect')});},theme);
  assert.equal(await page.locator('html').getAttribute('data-theme'),theme);assert.equal(await page.locator('#conceptMap [data-node]').count(),12);
  assert.equal(await page.locator('#conceptMap [data-map-background]').getAttribute('fill'),theme==='light'?'#f6f9f7':'#101b21');
  for(const width of [320,390,768,1440]){await page.setViewportSize({width,height:900});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'overflow '+theme+' '+width);}
  await page.setViewportSize({width:390,height:900});await page.locator('#mapCard').screenshot({path:path.join(output,'map-'+theme+'-mobile.png')});
  if(theme==='light'){
   await page.locator('#mapCard .chart-data-details').evaluate(element=>element.open=true);
   assert.equal(await page.locator('#mapRelations td').first().evaluate(element=>getComputedStyle(element,'::before').color),'rgb(36, 91, 145)','mobile table labels need strong contrast in light mode');
   await page.locator('#mapCard .chart-data-details').evaluate(element=>element.open=false);
  }
 }
 await page.reload({waitUntil:'networkidle'});assert.equal(await page.locator('html').getAttribute('data-theme'),'light','appearance must survive restart');
 await page.evaluate(()=>{whisperKey='gsk_test';notesText='Progetto precedente da conservare';$('notesTextarea').value=notesText;showPanel('saved');});
 const bytes=26*1024*1024,buffer=Buffer.alloc(bytes+44);buffer.write('RIFF',0);buffer.writeUInt32LE(bytes+36,4);buffer.write('WAVEfmt ',8);buffer.writeUInt32LE(16,16);buffer.writeUInt16LE(1,20);buffer.writeUInt16LE(1,22);buffer.writeUInt32LE(16000,24);buffer.writeUInt32LE(32000,28);buffer.writeUInt16LE(2,32);buffer.writeUInt16LE(16,34);buffer.write('data',36);buffer.writeUInt32LE(bytes,40);for(let i=44;i<buffer.length;i+=2)buffer.writeInt16LE(4000,i);
 await page.locator('#audioFileInput').setInputFiles({name:'audio-26mb.wav',mimeType:'audio/wav',buffer});
 await page.waitForFunction(()=>!$('workIndicator').hidden);await page.evaluate(()=>showPanel('notes'));assert(await page.locator('#workIndicator').isVisible());await page.locator('#workIndicator').screenshot({path:path.join(output,'import-progress-light.png')});
 await page.waitForFunction(()=>!isProcessing,{},{timeout:90000});assert.equal(requests,2,'quota must stop further uploads');assert.equal(await page.evaluate(()=>notesText),'Progetto precedente da conservare');
 const record=await page.evaluate(()=>savedRecordings.find(record=>record.importPending));assert(record);assert.equal(record.entries.length,1);assert.equal(await page.evaluate(async id=>(await getSessionAudio(id)).size,record.id),buffer.length);
 failAt=-1;await page.evaluate(id=>{window.importResumePromise=retryBrowserImport(id);},record.id);await page.waitForFunction(()=>isProcessing);await page.waitForFunction(()=>!isProcessing,{},{timeout:90000});
 const restored=await page.evaluate(id=>({record:savedRecordings.find(record=>record.id===id),entries:transcriptEntries,notes:notesText}),record.id);
 assert.equal(restored.record.importPending,false);assert.equal(restored.entries[0].text,'Passaggio 1 completo, senza tagli.');assert.equal(requests,restored.entries.length+1,'resume must reuse the first completed part');assert(uploads.every(size=>size<20*1024*1024));
 const parts=await page.evaluate(()=>getChunksFromDB(AUDIO_SESSION));assert.equal(parts.length,restored.entries.length);assert.equal(restored.entries.at(-1).endMs,bytes*1000/32000,'final audio duration was cut');assert(await page.locator('#workIndicator').isHidden());
 // Even an unavailable module must leave a readable complete concept list, never a blank rectangle.
 await page.evaluate(map=>{disposeConceptViewer();window.VoiceConceptMap=undefined;renderConceptMap(map);showPanel('ai');},map);assert(await page.locator('#mapFallback').isVisible());assert.equal(await page.locator('#mapFallback li').count(),12);assert(await page.locator('#mapViewport').isHidden());
 assert.equal(errors.length,0,errors.join('\n'));console.log(JSON.stringify({themes:'light/dark, persisted; 4 widths',largeAudioMB:26,parts:parts.length,requests,quota:'stops, preserves original, resumes cached parts',progress:'visible between panels',fallback:'12 readable concepts',errors}));await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
