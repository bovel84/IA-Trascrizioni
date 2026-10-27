#!/usr/bin/env node
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const {createEnvironment}=require('./dom-stub');
const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8'),ctx=createEnvironment({html});
const run=code=>vm.runInContext(code,ctx);[...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].forEach(m=>run(m[1]));
let checks=0;function check(name,value){assert.ok(value,name);checks++;console.log('ok  '+name);}
(async()=>{
 await run('init()');
 run(`speakers=['Anna','Luca']; transcriptEntries=[
 {speaker:1,speakerName:'Anna',nativeVoiceId:'session/voice-1',text:'La frase inizia',offsetMs:0,time:'00:00',endMs:30000},
 {speaker:1,speakerName:'Anna',nativeVoiceId:'session/voice-1',text:'e continua nel secondo segmento.',offsetMs:30000,time:'00:30',endMs:60000},
 {speaker:2,speakerName:'Luca',text:'Interviene Luca.',offsetMs:60000,time:'01:00',endMs:65000},
 {speaker:1,speakerName:'Anna',text:'Anna risponde.',offsetMs:65000,time:'01:05',endMs:70000}];`);
 const original=run('JSON.stringify(transcriptEntries)');
 check('consecutive chunks of one person become one complete intervention',run('transcriptTurns().length')===3&&run('transcriptTurns()[0].text')==='La frase inizia e continua nel secondo segmento.');
 check('real speaker changes retain chronological dialogue order',run('transcriptTurns().map(t=>t.speaker).join()')==='1,2,1');
 check('source fragments and timestamps are never mutated',run('JSON.stringify(transcriptEntries)')===original&&run('subtitleBlocks().length')===4&&run('subtitleBlocks()[1].start')===30000);
 check('AI input has one person heading per intervention without chunk timestamps',run('analysisSource("transcript").text')==='Anna:\nLa frase inizia e continua nel secondo segmento.\n\nLuca:\nInterviene Luca.\n\nAnna:\nAnna risponde.');
 check('punctuation at a chunk boundary is joined without an extra space',run(`joinTranscriptFragments(['Totale 12,50','€; sì, sì',', confermato.'])`)==='Totale 12,50 €; sì, sì, confermato.');
 check('intentional repetitions are preserved',run(`joinTranscriptFragments(['sì','sì, davvero.'])`)==='sì sì, davvero.');
 run(`transcriptEntries.splice(1,0,{speaker:0,speakerName:'Non assegnato',text:'Parole incerte.',offsetMs:20000,time:'00:20'});`);
 check('unknown speech never inherits the neighboring voice',run('transcriptTurns().length')===5&&run('transcriptTurns()[1].speaker')===0);
 run(`chooseEntrySpeaker(1);$('entrySpeakerSelect').value='1';confirmEntrySpeaker();`);
 check('explicit manual assignment joins a fragment to its confirmed person',run('transcriptTurns().length')===3&&run('transcriptTurns()[0].text').includes('Parole incerte.'));
 run(`transcriptEntries[2].text='Continuazione corretta manualmente.';refreshTranscript();`);
 check('editing an original fragment is reflected in the continuous text',run('transcriptTurns()[0].text').endsWith('Continuazione corretta manualmente.'));
 let output='';ctx.capture=(name,text)=>output=text;run('downloadText=capture;exportTXT();');
 check('TXT report contains the continuous intervention once',output.includes('Anna:\nLa frase inizia Parole incerte. Continuazione corretta manualmente.')&&!output.includes('[00:30]'));
 run('exportMarkdown();');check('Markdown report uses one heading and readable body per intervention',output.includes('**Anna**\n\nLa frase inizia Parole incerte. Continuazione corretta manualmente.'));
 check('editorial instructions remove technical parts without guessing voices',['correct','improve'].every(action=>run(`AI_INSTRUCTIONS.${action}`).includes('Non inserire timestamp')&&run(`AI_INSTRUCTIONS.${action}`).includes('Non attribuire')));
 run(`speakers=['Voce 1','Voce 2'];transcriptEntries=[
 {speaker:1,speakerName:'Voce 1',nativeSegmentId:'session-a/0.wav#0',text:'proprio nelle scorse settimane anche',offsetMs:13000,endMs:17000,time:'00:13'},
 {speaker:0,speakerName:'Non assegnato',speakerReview:true,nativeSegmentId:'session-a/0.wav#1',text:'con',offsetMs:17100,endMs:17500,time:'00:17'},
 {speaker:1,speakerName:'Voce 1',nativeSegmentId:'session-a/0.wav#2',text:'i serbi di Bosnia.',offsetMs:17800,endMs:24000,time:'00:17'}];refreshTranscript();`);
 const screenshotSource=run('JSON.stringify(transcriptEntries)');
 check('the screenshot single-word island no longer creates three cards',run('transcriptTurns().length')===1&&run('transcriptTurns()[0].displayText')==='proprio nelle scorse settimane anche con i serbi di Bosnia.');
 check('the uncertain word retains its real unassigned source identity',run('transcriptEntries[1].speaker')===0&&run('JSON.stringify(transcriptEntries)')===screenshotSource&&run('subtitleBlocks()[1].speaker')==='Non assegnato');
 check('AI and reports retain an explicit inline uncertainty marker',run('analysisSource("transcript").text').includes('[Non assegnato: con]'));
 run('exportTXT();');check('TXT retains the uncertain word without silently assigning its speaker',output.includes('anche [Non assegnato: con] i serbi di Bosnia.'));
 run('transcriptEntries[0].speakerReview=transcriptEntries[2].speakerReview=true;');
 check('a candidate voice remains reviewable without isolating each tiny unknown word',run('transcriptTurns().length')===1&&run('transcriptTurns()[0].speakerReview')&&run('transcriptEntries[1].speaker')===0);
 run('transcriptEntries[0].speakerReview=transcriptEntries[2].speakerReview=false;');
 run("$('transcriptSearch').value='anche con';refreshTranscript();");
 check('search spans a highlighted unassigned word without losing the sentence',ctx.__elements.get('transcriptArea').children.length===1&&run('transcriptTurnHtml(transcriptTurns()[0])').includes('<mark>con</mark>'));
 run("chooseEntrySpeaker(1);$('entrySpeakerSelect').value='1';confirmEntrySpeaker();");
 check('manual confirmation removes the inline marker without changing the words',run('transcriptTurns().length')===1&&!run('transcriptTurns()[0].text').includes('Non assegnato')&&run('transcriptEntries[1].speaker')===1);
 run("Object.assign(transcriptEntries[1],{speaker:0,speakerName:'Non assegnato',speakerReview:true});");
 run("$('transcriptSearch').value='';transcriptEntries[2].speaker=2;transcriptEntries[2].speakerName='Voce 2';");
 check('a real speaker change is never hidden by the continuity view',run('transcriptTurns().length')===3);
 run("transcriptEntries[2].speaker=1;transcriptEntries[2].speakerName='Voce 1';transcriptEntries[2].offsetMs=22000;");
 check('a long pause cannot attach an unknown word to a distant voice',run('transcriptTurns().length')===3);
 run("transcriptEntries[2].offsetMs=17800;transcriptEntries[2].nativeSegmentId='session-b/0.wav#0';");
 check('fragments from different recordings cannot bridge each other',run('transcriptTurns().length')===3);
 run(`transcriptEntries=[{speaker:0,speakerName:'Non assegnato',text:'un frammento',offsetMs:0,endMs:500},{speaker:0,speakerName:'Non assegnato',text:'non riconosciuto',offsetMs:700,endMs:1300}];`);
 check('adjacent unknown fragments form a clearly unassigned passage',run('transcriptTurns().length')===1&&run('transcriptTurns()[0].speaker')===0&&run('transcriptTurns()[0].text')==='un frammento non riconosciuto');
 run(`transcriptEntries=[{speaker:1,speakerName:'Anna',text:'Prima frase.',offsetMs:0,endMs:1000},{speaker:1,speakerName:'Anna',text:'Dopo una pausa.',offsetMs:4000,endMs:5000}];`);
 check('a meaningful pause inserts a paragraph within the same intervention',run('transcriptTurns().length')===1&&run('transcriptTurns()[0].text')==='Prima frase.\n\nDopo una pausa.');
 console.log(`${checks} continuous transcript checks passed.`);
})().then(()=>process.exit(0)).catch(e=>{console.error(e);process.exit(1)});
