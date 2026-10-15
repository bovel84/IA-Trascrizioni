const {chromium} = require('playwright');
const path = require('path');
const fs = require('fs');
const root=path.resolve(__dirname,'..');
const output=process.env.PREVIEW_OUTPUT || path.join(root,'preview-output');
fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN || undefined,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1440,height:1050},deviceScaleFactor:1});
 const errors=[]; page.on('pageerror',e=>errors.push(e.message));
 await page.route('https://**/*',r=>{
  const url=r.request().url();
  if(url.includes('chart.umd')) return r.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/chart.umd.js'),contentType:'application/javascript'});
  if(url.includes('jspdf.umd')) return r.fulfill({path:path.join(root,'android/app/src/main/assets/vendor/jspdf.umd.min.js'),contentType:'application/javascript'});
  return r.abort();
 });
 await page.goto('http://127.0.0.1:8088',{waitUntil:'networkidle'});
 await page.screenshot({path:path.join(output,'desktop.png'),fullPage:true});
 await page.evaluate(()=>{speakers=['Anna','Marco'];transcriptEntries=[{speaker:1,speakerName:'Anna',text:'Partiamo dagli obiettivi della riunione. Vorrei fare il punto sul progetto e definire insieme i prossimi passi.',time:'00:12',offsetMs:12000},{speaker:2,speakerName:'Marco',text:'La prima versione è pronta. Possiamo raccogliere i commenti del gruppo e programmare una nuova verifica per venerdì.',time:'00:28',offsetMs:28000}];refreshTranscript();updateSpeakerChips();});
 const results=[];
 for(const width of [320,390,768,1440]) {
  await page.setViewportSize({width,height:900});
  for(const name of ['record','notes','ai','export','saved']) {
   await page.locator(`[data-tab="${name}"]`).click();
   await page.waitForTimeout(150);
   const info=await page.evaluate(name=>({name,scroll:document.documentElement.scrollWidth,width:innerWidth,visible:document.getElementById(name+'Tab').getBoundingClientRect().height>0}),name);
   if(info.scroll>width+1||!info.visible) throw Error(JSON.stringify({width,...info}));
   results.push(`${width}:${name}`);
  }
  await page.locator('[data-tab="record"]').click();
  await page.locator('.settings-trigger').click();
  if(!await page.locator('#whisperKeyInput').isVisible()) throw Error('Settings unavailable');
  await page.locator('#connectionSettings>summary').click();
  if(width===390) {await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:path.join(output,'mobile.png'),fullPage:true});}
 }
 if(await page.locator('button.export-card').count()!==8) throw Error('Export actions lost');
 await page.locator('[data-tab="notes"]').click();await page.waitForTimeout(200);
 if(!(await page.locator('#drawingCanvas').boundingBox())?.width) throw Error('Drawing canvas hidden');
 console.log(JSON.stringify({panels:results.length,errors,exports:8,canvas:'visible'}));
 await browser.close();if(errors.length)process.exit(1);
})().catch(e=>{console.error(e);process.exit(1)});
