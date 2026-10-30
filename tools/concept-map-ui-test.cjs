const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict');
const map=require('./fixtures/concept-map.json'),root=path.resolve(__dirname,'..'),output=process.env.PREVIEW_OUTPUT||path.join(root,'preview-output');
fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN||undefined,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:390,height:900},deviceScaleFactor:1,hasTouch:true,acceptDownloads:true});
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
 await page.route('https://**/*',route=>{
  const url=route.request().url();
  if(url.includes('chart.umd'))return route.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/chart.umd.js'),contentType:'application/javascript'});
  if(url.includes('jspdf.umd'))return route.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/jspdf.umd.min.js'),contentType:'application/javascript'});
  return route.abort();
 });
 await page.goto('http://127.0.0.1:8088',{waitUntil:'networkidle'});
 // Restore while the AI panel is hidden, then reveal it: fit must use its real size.
 await page.evaluate(map=>{restoreAnalysisState({concept:map});showPanel('ai');},map);
 await page.waitForTimeout(100);
 assert.equal(await page.locator('#conceptMap [data-node]').count(),12);assert.equal(await page.locator('#conceptMap [data-edge]').count(),11);
 assert.equal(await page.evaluate(()=>conceptViewer.graph.roots[0]),'mini');
 assert.equal(await page.evaluate(()=>conceptViewer.selected),'mini','phone maps should start at a readable topic, not microscopic overview');
 assert(await page.evaluate(()=>conceptViewer.scale)>=.8);await page.locator('#mapCard').screenshot({path:path.join(output,'concept-readable-mobile.png')});
 assert(await page.evaluate(()=>conceptViewer.graph.nodes.some(n=>n.side===-1)&&conceptViewer.graph.nodes.some(n=>n.side===1)));
 assert(await page.locator('#conceptMap [data-edge] text').count()>=11,'relationships must be visible on the diagram');
 const full=await page.evaluate(()=>conceptExportBox().join(' '));
 for(const width of [320,390,768,1440]){
  await page.setViewportSize({width,height:900});await page.evaluate(()=>fitMap());await page.waitForTimeout(100);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'page overflow '+width);
  await page.locator('#mapViewport').scrollIntoViewIfNeeded();
  await page.locator('#mapCard').screenshot({path:path.join(output,`concept-overview-${width}.png`)});
 }
 await page.setViewportSize({width:390,height:900});await page.evaluate(()=>fitMap());
 // Real pointer capture must still select a node; not only programmatic selection.
 await page.locator('#conceptMap [data-node="fund"]').click();
 await page.waitForFunction(()=>conceptViewer.selected==='fund');
 assert(await page.locator('#mapConceptDetail').isVisible());assert((await page.locator('#mapConceptDetail').innerText()).includes('Attribuzione vocale da verificare'));
 assert((await page.locator('#mapConceptDetail').innerText()).includes('[Non assegnato: veicolo]'));
 assert.equal(await page.evaluate(()=>conceptMapData.nodes.find(n=>n.id==='fund').kind),'proposta');
 await page.locator('#mapConceptDetail .map-neighbor').filter({hasText:'Strumento di progetto'}).click();
 assert.equal(await page.evaluate(()=>conceptViewer.selected),'vehicle');
 await page.locator('#mapNodeSelect').selectOption('mini');
 const zoom=await page.evaluate(()=>conceptViewer.scale);await page.locator('[onclick="zoomMap(1.25)"]').click();assert(await page.evaluate(()=>conceptViewer.scale)>zoom);
 await page.locator('#mapExpandBtn').click();assert.equal(await page.locator('#mapExpandBtn').getAttribute('aria-expanded'),'true');
 await page.waitForTimeout(80);assert.equal(await page.evaluate(()=>conceptViewer.selected),'mini');
 await page.locator('#mapCard').screenshot({path:path.join(output,'concept-expanded-mobile.png')});
 await page.keyboard.press('Escape');assert.equal(await page.locator('#mapExpandBtn').getAttribute('aria-expanded'),'false');
 await page.locator('#mapLayoutSelect').selectOption('levels');assert.equal(await page.evaluate(()=>conceptViewer.graph.mode),'levels');assert.equal(await page.evaluate(()=>conceptViewer.selected),'mini');
 await page.locator('#mapLayoutSelect').selectOption('branches');
 await page.locator('#conceptMap [data-node="risk"]').focus();await page.keyboard.press('Enter');assert.equal(await page.evaluate(()=>conceptViewer.selected),'risk');
 assert.equal(await page.locator('#conceptMap [data-node="risk"]').getAttribute('aria-pressed'),'true');
 await page.evaluate(()=>fitMap());await page.locator('#mapViewport').scrollIntoViewIfNeeded();
 const view=await page.locator('#mapViewport').boundingBox(),before=await page.locator('#conceptMap').getAttribute('viewBox');
 await page.mouse.move(view.x+15,view.y+15);await page.mouse.down();await page.mouse.move(view.x+90,view.y+50,{steps:6});await page.mouse.up();
 assert.notEqual(await page.locator('#conceptMap').getAttribute('viewBox'),before,'drag must pan the diagram');
 // Pinch with two real touch pointers. This must zoom the map, not the page.
 await page.evaluate(()=>fitMap());const cdp=await context.newCDPSession(page),box=await page.locator('#mapViewport').boundingBox(),x=box.x+box.width/2,y=box.y+box.height/2,oldScale=await page.evaluate(()=>conceptViewer.scale);
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:x-25,y,id:1},{x:x+25,y,id:2}]});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x-55,y,id:1},{x:x+55,y,id:2}]});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 assert(await page.evaluate(()=>conceptViewer.scale)>oldScale*1.4,'pinch must increase diagram zoom');
 // Downloads use the whole original canvas, even after zoom, filtering and panning.
 await page.locator('#mapNodeSelect').selectOption('fund');
 async function download(action){const [file]=await Promise.all([page.waitForEvent('download'),page.locator(`[onclick="${action}()"]`).click()]);assert.equal(await file.failure(),null);const target=path.join(output,file.suggestedFilename());await file.saveAs(target);return target;}
 const svg=await download('exportConceptSvg'),source=fs.readFileSync(svg,'utf8');assert(source.includes('viewBox="'+full+'"'),'SVG was cropped to the active viewport');
 assert.equal((source.match(/data-node=/g)||[]).length,12);assert.equal((source.match(/data-edge=/g)||[]).length,11);assert(!source.includes('opacity="0.12"'),'export must restore hidden relationships');
 const png=await download('exportConceptPng');assert.equal(fs.readFileSync(png).subarray(1,4).toString(),'PNG');
 // Save and reopen the actual enhanced map, including the uncertain node details.
 await page.evaluate(async()=>{$('sessionNameInput').value='Mappa di prova';await confirmSaveSession();});const id=await page.evaluate(()=>savedRecordings.at(-1).id);
 await page.evaluate(async id=>{await newTranscript();await loadRec(id);showPanel('ai');},id);await page.waitForTimeout(100);
 assert.equal(await page.locator('#conceptMap [data-node]').count(),12);await page.locator('#mapNodeSelect').selectOption('fund');assert((await page.locator('#mapConceptDetail').innerText()).includes('[Non assegnato: veicolo]'));
 await page.locator('#mapExpandBtn').click();await page.evaluate(()=>newTranscript());assert.equal(await page.evaluate(()=>mapExpanded),false);assert.notEqual(await page.evaluate(()=>document.body.style.overflow),'hidden');
 assert.equal(errors.length,0,errors.join('\n'));console.log(JSON.stringify({concepts:12,relations:11,viewports:4,touch:'tap, pan and pinch passed',exports:'complete SVG and PNG',project:'restored',errors}));await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
