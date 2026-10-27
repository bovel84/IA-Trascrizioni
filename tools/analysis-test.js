#!/usr/bin/env node
const fs=require('fs'),vm=require('vm'),assert=require('assert/strict'),path=require('path');
const {createEnvironment}=require('./dom-stub');
const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8');
const ctx=createEnvironment({html}),run=code=>vm.runInContext(code,ctx),el=id=>ctx.__elements.get(id);
const requests=[];let respond=body=>/^(Correggi esclusivamente|Rielabora)/.test(body.messages.at(-1).content)?apiText(body.messages.at(-1).content.split('\n\nDOCUMENTO:\n').at(-1)):({choices:[{finish_reason:'stop',message:{content:'SINTESI\nBudget confermato.\nDECISIONI CONFERMATE\nNon indicate.\nPROSSIMI PASSI\nResponsabile: Non indicato.'}}]});
ctx.nativeAiRequest=async(provider,endpoint,key,body,signal)=>{requests.push({provider,endpoint,key,body,signal});return{ok:true,status:200,json:async()=>respond(body,signal)};};
let passed=0;
function check(label,condition){assert.ok(condition,label);passed++;console.log('ok  '+label);}
const set=values=>Object.entries(values).forEach(([k,v])=>run(`${k}=${JSON.stringify(v)}`));
const apiText=text=>({choices:[{finish_reason:'stop',message:{content:text}}]});
async function main(){
 for(const match of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi))run(match[1]);
 await run('init()');
 set({whisperKey:'gsk_test',llamaKey:'gsk_test',notesText:'Budget: 1.250,50 euro. Spese: 800 euro.',transcriptEntries:[{speaker:1,speakerName:'Anna',text:'Confermo il budget.',offsetMs:0,time:'00:00'},{speaker:2,speakerName:'Marco',text:'La scadenza è da concordare.',offsetMs:1000,time:'00:01'}]});
 el('aiSourceSelect').value='all';
 const originals=run('JSON.stringify(transcriptEntries)');
 for(const action of ['correct','summary','keypoints','improve']){
  await run(`runAI('${action}')`);
  check(action+' completes and stores a reusable result',run(`aiResults.some(r=>r.action==='${action}'&&r.content.includes('Budget'))`));
 }
 check('analysis never overwrites original transcript',run('JSON.stringify(transcriptEntries)')===originals);
 check('instructions preserve names amounts uncertainty and deadlines',requests.every(r=>r.body.messages[0].content.includes('Non inventare')&&r.body.messages[0].content.includes('attribuzioni')));
 check('key points request distinguishes proposals from confirmed decisions',requests.some(r=>r.body.messages[1].content.includes('stato confermato/proposto')));
 const beforeLocal=requests.length; set({llamaKey:'',whisperKey:''});
 await run("runAI('chart')");
 check('participation chart works without key or network',requests.length===beforeLocal&&run('chartData.labels.length')===2);
 check('participation uses measured word counts',run('chartData.series[0].data.join(",")')==='3,5');
 check('chart has no animation race during export and handles responsive canvas',run('chartInstance.config.options.animation')===false&&run('chartInstance.config.options.maintainAspectRatio')===false);
 set({llamaKey:'gsk_test',whisperKey:'gsk_test'});
 respond=()=>apiText(JSON.stringify({topic:'Riunione',nodes:[{id:'budget',label:'Budget'},{id:'decisioni',label:'Decisioni'}],links:[{from:'budget',to:'decisioni',label:'richiede'}]}));
 await run("runAI('concept')");
 check('concept map contains validated relationships',run('conceptMapData.links[0].label')==='richiede');
 check('all six tools retain their last result independently',run('aiResults.length')===6);
 check('diagrams are visible only after successful generation',!el('chartCard').hidden&&!el('mapCard').hidden);
 check('map SVG contains explicit exportable text colours',el('conceptMap').children.some(e=>e.tagName==='G'&&e.children.some(c=>c.getAttribute('fill')==='#e1eee5')));
 check('invalid duplicate map IDs are rejected',(()=>{try{run('normalizeConceptMap({nodes:[{id:"x",label:"a"},{id:"x",label:"b"}],links:[]})');return false;}catch(e){return true;}})());
 const previousMap=run('JSON.stringify(conceptMapData)');
 respond=()=>apiText('{"nodes":[{"id":"a","label":"A"}],"links":[{"from":"a","to":"missing"}]}');
 await run("runAI('concept')");
 check('invalid map preserves last valid output and unlocks controls',run('JSON.stringify(conceptMapData)')===previousMap&&!run('aiJob'));
 check('map errors explain the actual invalid relationship',el('aiToolStatus').textContent.includes('inesistente'));
 el('chartModeSelect').value='numbers';
 const numeric={title:'Importi',type:'bar',unit:'euro',labels:['Budget','Spese'],series:[{name:'Importo',data:[1250.5,800],evidence:['Budget: 1.250,50 euro.','Spese: 800 euro.']}]};
 respond=()=>apiText(JSON.stringify(numeric));await run("runAI('chart')");
 check('numeric chart validates literal evidence and Italian decimal amounts',run('chartData.series[0].data[0]')===1250.5);
 const previousChart=run('JSON.stringify(chartData)'); numeric.series[0].data[0]=999;
 respond=()=>apiText(JSON.stringify(numeric));await run("runAI('chart')");
 check('fabricated chart numbers are rejected without erasing prior chart',run('JSON.stringify(chartData)')===previousChart&&el('aiToolStatus').textContent.includes('non verificabile'));
 respond=()=>apiText('{"insufficient":true}');await run("runAI('chart')");
 check('absence of quantitative data is reported honestly',el('aiToolStatus').textContent.includes('Non ci sono dati numerici'));
 for(const data of [{labels:['A'],series:[{data:[]}]},{labels:['A'],series:[{data:[NaN]}]},{type:'pie',labels:['A'],series:[{data:[-1]}]}]){
  let rejected=false;try{ctx.invalid=data;run('normalizeChartData(invalid)');}catch(e){rejected=true;}check('malformed chart is rejected '+passed,rejected);
 }
 respond=()=>apiText('Sintesi valida');await run("runAI('summary')");
 let copied='';ctx.navigator.clipboard.writeText=async text=>{copied=text;};await run('copyAIResult()');
 check('copy exports selected result with title',copied.includes('Sintesi della riunione')&&copied.includes('Sintesi valida'));
 run('appendAIResultToNotes()');const notes=run('notesText');run('appendAIResultToNotes()');
 check('adding a result to notes is explicit and avoids duplicates',notes===run('notesText')&&notes.includes('Sintesi valida'));
 check('result and notes are included in autosave',JSON.parse(ctx.localStorage.getItem('voicescribe_autosave')).aiResults.length===6);
 el('sessionNameInput').value='Progetto completo';await run('confirmSaveSession()');
 const saved=run('savedRecordings.at(-1)');check('project stores all tool results',saved.aiResults.length===6);
 await run('newTranscript()');check('new project clears all analysis',run('aiResults.length')===0&&el('chartCard').hidden&&el('mapCard').hidden);
 await run(`loadRec(${saved.id})`);check('reopening project restores results and diagrams',run('aiResults.length')===6&&run('chartData.title')==='Importi'&&run('conceptMapData.nodes.length')===2);
 set({transcriptEntries:[],notesText:'Appunti senza registrazione',aiResults:[],chartData:null,conceptMapData:null});run('autosaveNow(); notesText="";');await run('checkAutosave()');
 check('notes-only drafts survive closing and restore',run('notesText')==='Appunti senza registrazione');
 // Delayed responses must not alter a changed document, or a newly opened project.
 let finish;ctx.nativeAiRequest=(provider,endpoint,key,body,signal)=>{requests.push({provider,endpoint,key,body,signal});return new Promise(resolve=>{finish=text=>resolve({ok:true,status:200,json:async()=>apiText(text)});});};
 let pending=run("runAI('summary')");const during=requests.length;await run("runAI('improve')");
 check('double clicks cannot start concurrent analysis',requests.length===during);
 set({notesText:'Modifica durante analisi'});finish('RISPOSTA VECCHIA');await pending;
 check('late response cannot overwrite modified input',!run('aiResults.some(r=>r.content.includes("RISPOSTA VECCHIA"))')&&el('aiToolStatus').textContent.includes('contenuto è cambiato'));
 pending=run("runAI('summary')");run('cancelAI()');finish('RISPOSTA ANNULLATA');await pending;
 check('cancel discards late results and immediately releases tool state',!run('aiJob')&&!run('aiResults.some(r=>r.content.includes("ANNULLATA"))'));
 pending=run("runAI('summary')");await run('newTranscript()');finish('ALTRA SESSIONE');await pending;
 check('new session cancels ongoing work and excludes responses from prior session',run('aiResults.length')===0&&run('notesText')==='');
 ctx.nativeAiRequest=async()=>({ok:true,status:200,json:async()=>({choices:[{finish_reason:'length',message:{content:'Risposta parziale'}}]})});
 set({notesText:'Testo di prova'});await run("runAI('summary')");
 check('truncated model output is never presented as complete',run('aiResults.length')===0&&el('aiToolStatus').textContent.includes('troncata'));
 let failures=0;ctx.nativeAiRequest=async()=>{failures++;throw Error('Offline');};await run("runAI('summary')");
 check('network failures do not repeat expensive model calls',failures===1&&!run('aiJob'));
 const chunks=run('splitAnalysisText("😀 testo ".repeat(4000),1000)');check('chunking preserves every source character including Unicode',chunks.join('')==='😀 testo '.repeat(4000)&&chunks.every(c=>c.length<=1001));
 const seen=[];ctx.nativeAiRequest=async(provider,endpoint,key,body)=>{seen.push({provider,key,body});return{ok:true,status:200,json:async()=>apiText(body.messages[1].content.split('DOCUMENTO:\n')[1])};};
 set({notesText:'Dato reale. '.repeat(5000)+'MARCATORE FINALE',aiResults:[]});await run("runAI('correct')");
 check('long corrections cover every part rather than silently truncating the source',seen.length>1&&run('aiResults[0].content').endsWith('MARCATORE FINALE'));
 check('all chunks use captured connection and credential',seen.every(c=>c.provider==='groq'&&c.key==='gsk_test'));
 // Files exported by the ordinary report actions must include analysis too.
 const blobs=[];ctx.URL={createObjectURL:blob=>{blobs.push(blob);return 'blob:test';},revokeObjectURL(){}};
 run('exportTXT(); exportMarkdown(); exportAIResult(); exportChartData();');
 check('TXT and Markdown include saved analysis outputs',(await blobs[0].text()).includes('ANALISI')&&(await blobs[1].text()).includes('Analisi del progetto'));
 check('standalone analysis export contains complete result',(await blobs[2].text()).includes('MARCATORE FINALE'));
 // Native cancellation closes pending requests and ignores callbacks after cancellation.
 const cancelled=[];ctx.AndroidRecorder={aiRequest(){},cancelAiRequest:id=>cancelled.push(id)};
 run(fs.readFileSync(path.join(__dirname,'..','native-bridge.js'),'utf8'));
 ctx.controller=new AbortController();const native=run("nativeAiRequest('groq','/chat/completions','test',{},controller.signal)");ctx.controller.abort();await assert.rejects(native,/annullata/);
 check('Android cancel reaches native connection and rejects pending UI promise',cancelled.length===1);
 ctx.nativeAiReply(cancelled[0],200,'{"choices":[]}','');check('native reply after cancellation is harmless',true);
 console.log(`${passed} analysis checks passed.`);
}
main().then(()=>process.exit(0)).catch(e=>{console.error(e);process.exit(1);});
