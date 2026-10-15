#!/usr/bin/env node
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert/strict');
const { createEnvironment } = require('./dom-stub');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const ctx = createEnvironment({ html });
const run = code => vm.runInContext(code, ctx);
[...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].forEach(m => run(m[1]));
const reply = { results: { utterances: [
    { start: 0, end: 1.4, speaker: 4, transcript: 'Prima persona.', words: [{ speaker_confidence: 0.95 }] },
    { start: 2, end: 3.5, speaker: 9, transcript: 'Seconda persona.', words: [{ speaker_confidence: 0.4 }] },
    { start: 5, end: 6, speaker: 4, transcript: 'Di nuovo la prima.', words: [{ speaker_confidence: 0.9 }] },
    { start: 6.1, end: 7, transcript: 'Voce incerta.' }
] } };
ctx.response = reply;
let count = 0;
function check(name, value) { assert.ok(value, name); count++; console.log('ok  ' + name); }
async function main() {
    await run('init()');
    const parsed = run('parseMeetingSpeakers(response)');
    check('non-sequential service IDs map to stable voices without guessing names', parsed.entries[0].speaker === parsed.entries[2].speaker && parsed.speakers.join(',') === 'Voce 1,Voce 2');
    check('missing labels stay unassigned and uncertain voices are marked', parsed.entries[3].speaker === 0 && parsed.entries[3].speakerReview && parsed.entries[1].speakerReview && !parsed.entries[0].speakerReview);
    check('real phrase start/end timestamps survive diarization', parsed.entries[1].offsetMs === 2000 && parsed.entries[1].endMs === 3500);
    assert.throws(() => run('parseMeetingSpeakers({results:{utterances:[{start:0,end:1,transcript:"x"}]}})'));
    assert.throws(() => run('parseMeetingSpeakers({results:{utterances:[{start:-1,end:1,transcript:"x",speaker:0}]}})'));
    check('missing diarization and malformed segments cannot replace originals', true);
    // Capture attribution must be taken before the async transcription returns.
    let finish;
    ctx.fetch = () => new Promise(resolve => { finish = resolve; });
    run("speakers=['Anna','Luca']; currentSpeaker=1; enqueueChunk(new Blob(['audio']),0); currentSpeaker=2;");
    finish({ok:true,status:200,json:async()=>({text:'Risposta in ritardo'})});
    while (run('liveProcessing')) await new Promise(r=>setTimeout(r,1));
    check('delayed transcription retains speaker captured with the audio', run('transcriptEntries[0].speakerName') === 'Anna');
    const audio = await run("assembleRecordedAudio([{chunk:new Blob(['a'])},{chunk:new Blob(['b'])}])");
    const wav = Buffer.from(await audio.arrayBuffer());
    check('separate containers become one WAV containing both decoded chunks', audio.type === 'audio/wav' && wav.toString('ascii',0,4)==='RIFF' && wav.readUInt32LE(40)===8 && wav.length===52);
    run("$('diarizationKey').value='test_deepgram'; whisperKey='gsk_do_not_send'; audioBlob=new Blob(['audio'],{type:'audio/wav'});");
    let requests = [];
    ctx.fetch = async (url,options) => { requests.push({url,options}); return {ok:true,status:200,json:async()=>reply}; };
    await run('diarizeMeeting()');
    check('whole recording uses exactly one Deepgram call and only its own key', requests.length===1 && requests[0].url.startsWith('https://api.deepgram.com/v1/listen?') && requests[0].options.headers.Authorization==='Token test_deepgram');
    check('completed analysis replaces transcript only after confirmation', run('transcriptEntries.length')===4 && run('speakers.length')===2);
    run('chooseEntrySpeaker(1); $("entrySpeakerSelect").value="1"; confirmEntrySpeaker();');
    check('manual corrections clear the uncertainty marker', run('transcriptEntries[1].speaker')===1 && !run('transcriptEntries[1].speakerReview'));
    ctx.prompt = () => 'Anna'; run('renameSpeaker(1)');
    check('rename updates all occurrences of the same voice', run('transcriptEntries[0].speakerName')==='Anna' && run('transcriptEntries[2].speakerName')==='Anna');
    check('subtitle export retains actual ending rather than next speaker start', run('subtitleBlocks()[0].end')===1400);
    run("window.nativeAudioSession='session-1790766000000'; window.nativeDiarizedSession=window.nativeAudioSession; autosaveNow();");
    check('autosave records native diarization state to prevent raw imports overwriting it', JSON.parse(ctx.localStorage.getItem('voicescribe_autosave')).nativeDiarizedSession==='session-1790766000000');
    run("$('sessionNameInput').value='Voci riunione'; window.AndroidRecorder={};");
    await run('confirmSaveSession()');
    check('saved project retains diarization and voice names', run('savedRecordings.at(-1).nativeDiarizedSession')==='session-1790766000000' && run('savedRecordings.at(-1).speakers[0]')==='Anna');
    run('window.AndroidRecorder=null; window.nativeAudioSession=null; window.nativeDiarizedSession=null;');
    const before = run('JSON.stringify(transcriptEntries)'); requests=[];
    ctx.fetch = async (url,options) => { requests.push({url,options}); return {ok:false,status:401}; };
    await run('diarizeMeeting()');
    check('API denial preserves edits and does not retry or call a chat model', requests.length===1 && run('JSON.stringify(transcriptEntries)')===before && !run('diarizationBusy'));
    ctx.fetch = async () => { run('transcriptEntries[0].text="Modifica durante analisi"'); return {ok:true,status:200,json:async()=>reply}; };
    await run('diarizeMeeting()');
    check('late analysis cannot overwrite concurrent manual edits', run('transcriptEntries[0].text')==='Modifica durante analisi');
    const edited = run('JSON.stringify(transcriptEntries)'); let confirms=0;
    ctx.confirm=()=>++confirms===1;
    ctx.fetch=async()=>({ok:true,status:200,json:async()=>reply});
    await run('diarizeMeeting()');
    check('declining apply keeps original transcript intact', run('JSON.stringify(transcriptEntries)')===edited);
    check('API key is excluded from autosaved project data', !JSON.stringify(run('sessionSnapshot()')).includes('test_deepgram'));
    console.log(`${count} speaker checks passed.`);
}
main().then(()=>process.exit(0)).catch(e=>{console.error(e);process.exit(1)});
