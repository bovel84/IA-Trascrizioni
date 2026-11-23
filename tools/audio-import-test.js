const assert=require('assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm');
const audio=require('../audio-import'),{createEnvironment}=require('./dom-stub');
let passed=0;function check(name,value){assert(value,name);passed++;console.log('ok  '+name);}
(async()=>{
 const original=new Blob([new Uint8Array(500)],{type:'audio/mpeg'}),small=[];await audio.split(original,(part,meta)=>small.push({part,meta}));check('small imports preserve the original format and bytes',small.length===1&&small[0].part===original&&small[0].meta.offsetMs===0);
 const bytes=26*1024*1024,block=new Uint8Array(65536);for(let i=0;i<block.length;i+=2){block[i]=16;block[i+1]=39;}
 const file=audio.wav(Array.from({length:bytes/block.length},()=>block),bytes),parts=[],progress=[];
 const fullRead=file.arrayBuffer;file.arrayBuffer=()=>{throw Error('Full source must never be buffered');};
 await audio.split(file,(blob,meta)=>parts.push({blob,...meta}),{onProgress:fraction=>progress.push(fraction)});
 let length=0;for(const part of parts){check('each WAV stays safely below the provider limit',part.blob.size<audio.MAX_UPLOAD);check('part offsets are continuous',part.offsetMs===length*1000/32000);length+=part.blob.size-44;}
 check('a source over 25 MB preserves every PCM byte and its final sample',length===bytes&&new DataView(await parts.at(-1).blob.slice(-2).arrayBuffer()).getInt16(0,true)===10000);check('preparation reaches complete progress',progress.at(-1)===1);file.arrayBuffer=fullRead;
 const encoderParts=[],encoder=new audio.Encoder(44100,(blob,meta)=>encoderParts.push({blob,...meta}));for(let i=0;i<44101;i++)encoder.sample(.25);await encoder.finish();check('fractional resampling keeps duration at the final buffer',encoderParts[0].blob.size-44===Math.ceil(44101*16000/44100)*2);
 const aborted=new AbortController();aborted.abort();await assert.rejects(()=>audio.split(file,()=>{}, {signal:aborted.signal}),{name:'AbortError'});check('cancel stops work before additional conversion',true);
 await assert.rejects(()=>audio.split(new Blob([]),()=>{}),/vuoto/);check('empty files fail visibly',true);
 const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8'),ctx=createEnvironment({html}),run=code=>vm.runInContext(code,ctx);
 for(const script of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi))run(script[1]);await run('init()');
 run("setWorkTask('import','Preparazione audio','Sorgente conservata',42);showPanel('notes');");check('progress remains available when changing panels',!ctx.__elements.get('workIndicator').hidden&&ctx.__elements.get('workProgress').value===42);
 run("clearWorkTask('import')");check('completion removes the work indicator',ctx.__elements.get('workIndicator').hidden);
 const vtt = new Blob(['WEBVTT\n\n00:08.000 --> 00:09.500\n<v Ada>Decisione della riunione.'], {type:'text/vtt'});
 vtt.name = 'Riunione Teams.vtt';
 const input = ctx.__elements.get('teamsTranscriptInput');
 const importTeams = () => input.onchange({target:{files:[vtt],value:''}});
 await importTeams();
 check('a VTT opens as a saved project in an empty workspace',run("transcriptOnly && savedRecordings.length === 1 && transcriptEntries[0].speakerName === 'Ada'"));
 const early = run(`parseTranscriptVtt('WEBVTT\\n\\n-00:01.000 --> 00:01.500\\n<v Ada>Prima frase</v>\\n\\n00:03.000 --> 00:04.000\\nSeconda frase')`);
 assert.deepEqual(Array.from(early.entries, entry => [entry.offsetMs, entry.endMs]), [[0, 2500], [4000, 5000]]);
 check('a Teams cue before time zero keeps its length and spacing',early.duration === '00:05');
 await run("saveChunkToDB(new Blob(['old audio']), AUDIO_SESSION, {transcriptionDone:false})");
 check('an imported transcript never picks up audio from a different project',await run('ensureAudio()') === null);
 await run('newTranscript()');
 check('closing a text project preserves audio awaiting recovery',await run('listChunkMetadata(AUDIO_SESSION).then(parts => parts.length)') === 1);
 await importTeams();
 check('a new VTT is saved without replacing the workspace when audio is awaiting recovery',run('savedRecordings.length === 2 && transcriptEntries.length === 0'));
 await run('clearChunksFromDB(AUDIO_SESSION);');
 await run('loadRec(savedRecordings[0].id)');
 run("whisperKey = 'gsk_test'");
 await run('startRecording()');
 check('a new web recording starts with a clean transcript and keeps the saved Teams project',
   run('isRecording && !transcriptOnly && transcriptEntries.length === 0 && savedRecordings[0].entries.length === 1'));
 console.log(passed+' audio-import checks passed.');process.exit(0);
})().catch(error=>{console.error(error);process.exitCode=1;});
