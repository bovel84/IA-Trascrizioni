const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const root=path.resolve(__dirname,'..'),output=process.env.PREVIEW_OUTPUT||path.join(root,'preview-output');
fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN||undefined,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:390,height:900},acceptDownloads:true});
 const page=await context.newPage(),errors=[],downloads=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
 await page.route('https://**/*',route=>{
  const url=route.request().url();
  if(url.includes('chart.umd'))return route.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/chart.umd.js'),contentType:'application/javascript'});
  if(url.includes('jspdf.umd'))return route.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/jspdf.umd.min.js'),contentType:'application/javascript'});
  if(url.includes('/models'))return route.fulfill({json:{data:[{id:'openai/gpt-oss-120b'},{id:'whisper-large-v3-turbo'}]}});
  if(url.includes('/chat/completions')){
   const body=route.request().postDataJSON(),prompt=body.messages.at(-1).content;let content;
   if(prompt.startsWith('Crea una mappa concettuale'))content=JSON.stringify({topic:'Piano operativo',nodes:[{id:'piano',label:'Piano operativo'},{id:'budget',label:'Budget approvato'},{id:'scadenze',label:'Scadenze da concordare'},{id:'anna',label:'Anna prepara la bozza'},{id:'marco',label:'Marco verifica i costi'},{id:'verifica',label:'Verifica di venerdì'}],links:[{from:'piano',to:'budget',label:'prevede'},{from:'piano',to:'scadenze',label:'richiede'},{from:'piano',to:'anna',label:'coinvolge'},{from:'piano',to:'marco',label:'coinvolge'},{from:'marco',to:'verifica',label:'presenta'}]});
   else if(prompt.startsWith('Estrai un solo insieme'))content=JSON.stringify({title:'Budget e spese',type:'bar',unit:'euro',labels:['Comunicazione','Spese operative'],series:[{name:'Importo',data:[1250,800],evidence:['Comunicazione: 1250 euro.','Spese operative: 800 euro.']}]});
   else if(prompt.startsWith('Correggi esclusivamente'))content='Anna: il budget è approvato. Marco: la scadenza è da concordare.';
   else if(prompt.startsWith('Rielabora'))content='Il gruppo approva il budget. Anna prepara la bozza; Marco verifica i costi. La scadenza resta da concordare.';
   else if(prompt.startsWith('Estrai PUNTI'))content='PUNTI CHIAVE\n• Budget approvato.\nAZIONI DA SVOLGERE\n• Anna prepara la bozza. Scadenza: Non indicato.\n• Marco verifica i costi. Scadenza: venerdì.\nQUESTIONI DA CHIARIRE\n• Data di consegna da concordare.';
   else content='SINTESI\nIl gruppo approva il budget e pianifica la verifica dei costi.\nDECISIONI CONFERMATE\nBudget approvato.\nQUESTIONI APERTE\nScadenza di consegna da concordare.\nPROSSIMI PASSI\nAnna prepara la bozza; Marco verifica i costi venerdì.';
   return route.fulfill({json:{choices:[{finish_reason:'stop',message:{content}}]}});
  }
  return route.abort();
 });
 await page.goto('http://127.0.0.1:8088',{waitUntil:'networkidle'});
 await page.evaluate(()=>{
  whisperKey=llamaKey='gsk_fixture';
  transcriptEntries=[{speaker:1,speakerName:'Anna',text:'Il budget è approvato. Preparo la bozza.',offsetMs:0,time:'00:00'},{speaker:2,speakerName:'Marco',text:'Verifico i costi venerdì. La consegna resta da concordare.',offsetMs:12000,time:'00:12'}];
  notesText='Comunicazione: 1250 euro. Spese operative: 800 euro.';document.getElementById('notesTextarea').value=notesText;
  document.getElementById('aiSourceSelect').value='all';refreshTranscript();showPanel('ai');
 });
 for(const action of ['correct','summary','keypoints','improve','chart','concept']){
  await page.locator(`[data-ai-action="${action}"]`).click();
  await page.waitForFunction(()=>!aiJob);
  assert(await page.evaluate(action=>aiResults.some(r=>r.action===action),action),'missing result '+action);
 }
 assert.equal(await page.locator('#aiResultSelect option').count(),6);
 await page.locator('#aiResultSelect').selectOption('summary');
 await page.locator('#appendAIResultBtn').click();
 assert(await page.evaluate(()=>notesText.includes('SINTESI')&&transcriptEntries[0].text.startsWith('Il budget')));
 await page.locator('#chartModeSelect').selectOption('numbers');await page.locator('[data-ai-action="chart"]').click();await page.waitForFunction(()=>!aiJob);
 assert.deepEqual(await page.evaluate(()=>chartData.series[0].data),[1250,800]);
 for(const width of [320,390,768,1440]){
  await page.setViewportSize({width,height:1000});await page.waitForTimeout(120);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'overflow '+width);
  assert.equal(await page.locator('[data-ai-action]').count(),6);
  await page.evaluate(()=>{scrollTo(0,0);document.getElementById('toast').classList.remove('show');});
  await page.screenshot({path:path.join(output,`tools-${width}.png`),fullPage:true});
 }
 async function download(action){
  const panel=['exportTXT','exportMarkdown','exportSRT','exportVTT','exportPDF','exportDrawing'].includes(action)?'export':'ai';
  await page.locator(`[data-tab="${panel}"]`).click();
  const button=page.locator(`[onclick="${action}()"]`);
  await button.scrollIntoViewIfNeeded();
  // Exercise real user actions, with time to finish each browser download.
  await page.waitForTimeout(150);
  let file;
  try { [file]=await Promise.all([page.waitForEvent('download'),button.click()]); }
  catch(error) { throw new Error(action+': '+error.message+' · '+await page.locator('#toast').innerText()); }
  assert.equal(await file.failure(),null);const target=path.join(output,file.suggestedFilename());await file.saveAs(target);downloads.push(target);return target;
 }
 for(const action of ['exportTXT','exportMarkdown','exportSRT','exportVTT','exportAIResult','exportChart','exportChartData','exportConceptSvg','exportConceptPng','exportPDF'])await download(action);
 const pdf=downloads.find(p=>p.endsWith('.pdf'));assert.equal(fs.readFileSync(pdf).subarray(0,4).toString(),'%PDF');
 assert(fs.readFileSync(downloads.find(p=>p.endsWith('.svg')),'utf8').includes('#e1eee5'),'SVG lost explicit text style');
 assert(fs.readFileSync(downloads.find(p=>p.endsWith('.txt')),'utf8').includes('SINTESI'),'TXT lost analysis');
 assert(fs.readFileSync(downloads.find(p=>p.endsWith('.csv')),'utf8').includes('1250'),'CSV lost source values');
 // Exercise pen, undo/redo, responsive redraw and the real drawing download.
 await page.locator('[data-tab="notes"]').click();await page.waitForTimeout(180);
 const box=await page.locator('#drawingCanvas').boundingBox();
 await page.mouse.move(box.x+30,box.y+30);await page.mouse.down();await page.mouse.move(box.x+100,box.y+90,{steps:8});await page.mouse.up();
 assert.equal(await page.evaluate(()=>drawingHistory.length),1);
 await page.evaluate(()=>{undoDrawing();redoDrawing();});assert.equal(await page.evaluate(()=>drawingHistory.length),1);
 await download('exportDrawing');
 const project=await page.evaluate(async()=>{document.getElementById('sessionNameInput').value='Riunione completa';await confirmSaveSession();return savedRecordings.at(-1).id;});
 await page.evaluate(async id=>{await newTranscript();await loadRec(id);showPanel('ai');},project);
 assert.equal(await page.evaluate(()=>aiResults.length),6);assert.equal(await page.locator('#chartCard').isVisible(),true);assert.equal(await page.locator('#mapCard').isVisible(),true);
 assert.equal(errors.length,0,errors.join('\n'));
 console.log(JSON.stringify({tools:6,downloads:downloads.length,viewports:4,project:'restored',errors,pdf}));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
