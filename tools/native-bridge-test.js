#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert/strict');
const { createEnvironment } = require('./dom-stub');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const source = fs.readFileSync(path.join(__dirname, '..', 'native-bridge.js'), 'utf8');
let state = { active: false, recording: false, paused: false, starting: false, status: 'Pronto', entries: [] };
const calls = [], ctx = createEnvironment({ html });
ctx.HTMLAnchorElement = class {
    click() { calls.push(['browserClick']); }
    dispatchEvent() { calls.push(['browserDispatch']); return true; }
};
ctx.FileReader = class {
    readAsDataURL(blob) { blob.arrayBuffer().then(data => { this.result = 'data:' + blob.type + ';base64,' + Buffer.from(data).toString('base64'); this.onload?.(); }); }
};
const webFetch = ctx.fetch;
ctx.fetch = (url, options) => /^(blob:|data:)/.test(String(url)) ? Promise.resolve({ blob: async () => new Blob(['PDF'], { type: 'application/pdf' }) }) : webFetch(url, options);
ctx.navigator.mediaDevices.getUserMedia = () => { throw Error('The WebView must never own the microphone'); };
ctx.AndroidRecorder = {
    state: () => JSON.stringify(state),
    sessions: () => JSON.stringify(state.sessionId ? [state] : []),
    start: (...args) => calls.push(['start', ...args]),
    canCapturePlayback: () => true,
    localVoicesAvailable: () => true,
    setLocalVoices: value => calls.push(['voiceMode',value]),
    renameVoice: (...args) => calls.push(['renameVoice',...args]),
    startPlayback: (...args) => calls.push(['startPlayback', ...args]),
    stop: () => calls.push(['stop']),
    pause: value => calls.push(['pause', value]),
    exportAudio: id => calls.push(['exportAudio', id]),
    retry: (...args) => calls.push(['retry', ...args]),
    batterySettings: () => calls.push(['battery']),
    download: (...args) => calls.push(['download', ...args])
};
const run = code => vm.runInContext(code, ctx);
let passed = 0;
function check(name, condition) { assert.ok(condition, name); passed++; console.log('ok  ' + name); }
async function main() {
    run(source);
    [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].forEach(match => run(match[1]));
    await run('init()');
    check('native adapter initialized once in the original UI', ctx.nativeRecorderReady === true);
    check('screen-off control describes native recording', ctx.__elements.get('backgroundToggle').checked && ctx.__elements.get('backgroundToggle').disabled);
    run("whisperKey = 'gsk_test'; sttModel = 'whisper-large-v3-turbo'; $('languageSelect').value = 'it';");
    await run('startRecording()');
    check('Record delegates microphone to the native service', calls[0]?.[0] === 'start' && calls[0][1] === 'gsk_test' && calls[0][3] === 'it');
    check('permission/start wait uses original processing UI', run('isProcessing'));
    state = { ...state, active: true, recording: true, sessionId: 'session-1790766000000', elapsedMs: 120000, chunkCount: 1, pending: 0,
        entries: [{ nativeSegmentId: 'session-1790766000000/000000000000.wav', offsetMs: 0, endMs: 30000, text: 'Prima frase', speaker: 2, speakerName: 'Persona 2' }] };
    run('syncNativeRecorder()');
    check('original recording button reflects native state', ctx.__elements.get('recordBtn').classList.contains('recording'));
    check('native transcript appears in the original transcript panel', run('transcriptEntries.length') === 1 && run('transcriptEntries[0].text') === 'Prima frase');
    check('native capture preserves selected speaker rather than arrival-time selection', run('transcriptEntries[0].speaker') === 2);
    check('native subtitles retain actual segment ending', run('transcriptEntries[0].endMs') === 30000);
    check('timer uses captured audio duration', ctx.__elements.get('timer').textContent === '02:00');
    run('syncNativeRecorder(); syncNativeRecorder();');
    check('repeated refresh does not duplicate transcript entries', run('transcriptEntries.length') === 1);
    run("transcriptEntries[0].text = 'Testo corretto'; syncNativeRecorder();");
    check('native refresh preserves user edits', run('transcriptEntries[0].text') === 'Testo corretto');
    Object.assign(state.entries[0],{transcriptQualityAvailable:true,transcriptReview:true,transcriptReviewReason:'Audio poco chiaro'});run('syncNativeRecorder()');
    check('new native quality metadata updates a seen segment without overwriting edits',run('transcriptEntries[0].transcriptReview')&&run('transcriptEntries[0].text')==='Testo corretto');
    run('confirmTranscriptEntry(0);syncNativeRecorder();');
    check('native synchronization preserves a manual text confirmation',run('isTextVerified(transcriptEntries[0])'));
    run('togglePause()');
    check('Pause uses the native recorder', calls.some(c => c[0] === 'pause' && c[1] === true));
    run("document.visibilityState = 'hidden';");
    await run('handleVisibilityChange()');
    check('hidden WebView does not stop native recording', !calls.some(c => c[0] === 'stop') && run('isRecording'));
    state.entries.push({ nativeSegmentId: 'session-1790766000000/000000120000.wav', offsetMs: 120000, text: 'Frase pronunciata a schermo bloccato' });
    state.elapsedMs = 240000; state.chunkCount = 2;
    run("document.visibilityState = 'visible'; syncNativeRecorder();");
    check('return from background imports missed segments with offsets', run('transcriptEntries.length') === 2 && run('transcriptEntries[1].offsetMs') === 120000);
    check('older unlabeled audio stays unassigned instead of claiming speaker one', run('transcriptEntries[1].speaker') === 0);
    // Native auto archive must not erase manual corrections or resurrect deleted phrases.
    run("const auto = savedRecordings.find(r => r.nativeAuto); auto.entries[0].text = 'Archivio corretto'; auto.entries.splice(1, 1);");
    state.entries.push({ nativeSegmentId: 'session-1790766000000/000000240000.wav', offsetMs: 240000, text: 'Terza frase' });
    state.elapsedMs = 270000; state.chunkCount = 3;
    run('syncNativeRecorder()');
    check('native archive preserves manual transcript corrections', run("savedRecordings.find(r => r.nativeAuto).entries[0].text") === 'Archivio corretto');
    check('native archive preserves deletions while adding new segments',
        run("savedRecordings.find(r => r.nativeAuto).entries.length") === 2 &&
        run("savedRecordings.find(r => r.nativeAuto).entries[1].text") === 'Terza frase');
    await run('stopRecording()');
    check('Stop delegates to service rather than browser recorder', calls.some(c => c[0] === 'stop'));
    state.active = false; state.recording = false; state.status = 'Sessione salvata e trascritta';
    run('syncNativeRecorder()');
    check('original UI returns to ready after service completes', !run('isRecording || isProcessing'));
    run("notesText = 'Appunti conservati'; $('sessionNameInput').value = 'Progetto originale';");
    await run('confirmSaveSession()');
    const project = run('savedRecordings.find(r => r.name === "Progetto originale")');
    check('Save Project retains native audio reference', project.hasAudio && project.nativeAudioSession === state.sessionId);
    check('Save Project keeps notes and transcript edits', project.notes === 'Appunti conservati' && project.entries[0].text === 'Testo corretto');
    await run('newTranscript()');
    run('syncNativeRecorder()');
    check('New does not resurrect a completed native transcript', run('transcriptEntries.length') === 0 && ctx.nativeAudioSession === null);
    await run(`loadRec(${project.id})`);
    check('opening a saved project restores native audio and notes', ctx.nativeAudioSession === state.sessionId && run('notesText') === 'Appunti conservati');
    await run('exportAudio()');
    check('audio export uses the selected native session', calls.some(c => c[0] === 'exportAudio' && c[1] === state.sessionId));
    run("$('audioFileInput').files = [new Blob(['audio'], { type: 'audio/wav' })];");
    ctx.__elements.get('audioFileInput').files[0].name = 'test.wav';
    const beforeImportFetch = ctx.fetch;
    ctx.fetch = async () => ({ ok: false, status: 401, json: async () => ({error:{message:'Chiave non valida'}}) });
    await run("$('audioFileInput').onchange({ target: $('audioFileInput') })");
    check('failed audio import preserves previous native audio and transcript', ctx.nativeAudioSession === state.sessionId && run('transcriptEntries.length') > 0);
    ctx.fetch = async () => ({ ok: true, status: 200, json: async () => ({text:'Nuovo audio importato'}) });
    await run("$('audioFileInput').onchange({ target: $('audioFileInput') })");
    ctx.fetch = beforeImportFetch;
    check('imported audio replaces the source transcript after success', run('transcriptEntries.length') === 1 && run('transcriptEntries[0].text') === 'Nuovo audio importato');
    check('imported audio is retained for draft recovery', (await run('getChunksFromDB(AUDIO_SESSION)')).length === 1);
    check('imported audio detaches the previous native audio reference', ctx.nativeAudioSession === null);
    run("nativeRecorderError('Permesso microfono negato')");
    check('denied permission leaves controls usable', !run('isProcessing'));
    run("const detachedPdf = new HTMLAnchorElement(); detachedPdf.download = 'documento.pdf'; detachedPdf.href = 'blob:test-pdf'; detachedPdf.dispatchEvent({ type: 'click' });");
    for (let i = 0; i < 20 && !calls.some(c => c[0] === 'download'); i++) await new Promise(resolve => setTimeout(resolve, 10));
    check('jsPDF detached synthetic download reaches Android save dialog with the file bytes', calls.some(c => c[0] === 'download' && c[1] === 'documento.pdf' && c[2] === 'application/pdf' && c[3] === 'UERG'));
    run(`window.nativeAudioSession = '${state.sessionId}'; window.nativeDiarizedSession = window.nativeAudioSession;`);
    const diarizedCount = run('transcriptEntries.length');
    state.entries.push({ nativeSegmentId: state.sessionId + '/late.wav', offsetMs: 300000, text: 'Late raw transcript' });
    run('syncNativeRecorder()');
    check('raw native refresh cannot duplicate an already diarized session', run('transcriptEntries.length') === diarizedCount);
    check('Android exposes the device audio choice', !ctx.__elements.get('nativeAudioSource').hidden && !ctx.__elements.get('playbackSourceOption').disabled);
    run("$('captureSourceSelect').value = 'playback'; $('captureSourceSelect').onchange();");
    check('device source preference survives UI reloads', run("localStorage.getItem('voicescribe_capture_source')") === 'playback');
    const microphoneStarts = calls.filter(c => c[0] === 'start').length;
    await run('startRecording()');
    check('device audio requests native projection rather than microphone capture', calls.some(c => c[0] === 'startPlayback' && c[1] === 'gsk_test' && c[3] === 'it') && calls.filter(c => c[0] === 'start').length === microphoneStarts);
    check('source is locked while waiting for Android consent', ctx.__elements.get('captureSourceSelect').disabled && run('isProcessing'));
    state = { ...state, active: true, recording: true, source: 'playback', status: 'Audio del dispositivo', sessionId: 'session-1790766000100', entries: [] };
    run("$('captureSourceSelect').value = 'microphone'; syncNativeRecorder();");
    check('returning to an active capture restores and locks the actual source', ctx.__elements.get('captureSourceSelect').value === 'playback' && ctx.__elements.get('captureSourceSelect').disabled);
    check('device capture tells the user to switch to the video app', ctx.__elements.get('bgWarning').textContent.includes('APP DEL VIDEO'));
    check('device capture explains screen lock limits', run("document.querySelector('.setting-info').innerHTML").includes('blocco schermo può interrompere'));
    const stops = calls.filter(c => c[0] === 'stop').length;
    run("document.visibilityState = 'hidden';"); await run('handleVisibilityChange()');
    check('switching to the video app keeps device capture running', calls.filter(c => c[0] === 'stop').length === stops && run('isRecording'));
    state = { ...state, active: false, recording: false, status: 'Condivisione audio terminata da Android' };
    run("document.visibilityState = 'visible'; syncNativeRecorder();");
    check('projection revocation releases controls and shows the native reason', !ctx.__elements.get('captureSourceSelect').disabled && !run('isProcessing') && ctx.__elements.get('recordHint').textContent.includes('terminata da Android'));
    await run('startRecording()');
    run("nativeRecorderError('Cattura audio del dispositivo annullata');");
    check('cancelled projection consent leaves recording controls usable', !run('isProcessing') && !ctx.__elements.get('captureSourceSelect').disabled);
    delete ctx.AndroidRecorder.startPlayback;
    await run('startRecording()');
    check('unavailable projection never silently falls back to the microphone', !run('isProcessing') && calls.filter(c => c[0] === 'start').length === microphoneStarts);
    check('native voice controls are exposed by a capable APK', !ctx.__elements.get('nativeVoiceControls').hidden);
    run("transcriptEntries=[{speaker:1,speakerName:'Andrea',text:'Intervento precedente',time:'00:00',offsetMs:0}];speakers=['Andrea','Persona 2'];window.nativeDiarizedSession=null;");
    state={active:true,recording:true,paused:false,source:'microphone',localVoices:true,sessionLocalVoices:true,voiceStatus:'2 voci distinte sul dispositivo',status:'Registrazione',sessionId:'session-1790766999000',elapsedMs:30000,chunkCount:1,entries:[
        {nativeSegmentId:'session-1790766999000/000000000000.wav#0',nativeVoiceId:'session-1790766999000/voice-1',speaker:1,speakerName:'Voce 1',speakerSource:'local',speakerReview:false,text:'Prima voce',offsetMs:100,endMs:900},
        {nativeSegmentId:'session-1790766999000/000000000000.wav#1',nativeVoiceId:'session-1790766999000/voice-2',speaker:2,speakerName:'Voce 2',speakerSource:'local',speakerReview:false,text:'Seconda voce',offsetMs:1100,endMs:1900},
        {nativeSegmentId:'session-1790766999000/000000000000.wav#2',speaker:0,speakerName:'Non assegnato',speakerSource:'local',speakerReview:true,speakerUnassignedReason:'overlap',text:'Voci sovrapposte',offsetMs:2100,endMs:2400}
    ]};run('syncNativeRecorder()');
    check('automatic native voices never inherit an unrelated participant name',run('transcriptEntries[1].speakerName')==='Voce 1'&&run('transcriptEntries[1].speaker')===2);
    check('distinct detected voices remain distinct in the project',run('transcriptEntries[2].speaker')===3&&run('transcriptEntries[2].speakerName')==='Voce 2');
    check('native voice provenance and timing survive import',run('transcriptEntries[1].speakerSource')==='local'&&run('transcriptEntries[1].nativeVoiceId')==='session-1790766999000/voice-1'&&run('transcriptEntries[1].endMs')===900);
    check('ambiguous audio stays visibly unassigned',run('transcriptEntries[3].speaker')===0&&run('transcriptEntries[3].speakerReview'));
    check('native overlap evidence survives import and prevents timing-only smoothing',run('transcriptEntries[3].speakerUnassignedReason')==='overlap');
    check('voice mode cannot change while capture is active',ctx.__elements.get('nativeVoiceModeSelect').disabled);
    ctx.prompt=()=> 'Marco';run('renameSpeaker(2)');
    check('renaming a local voice persists its session identity in native storage',calls.some(c=>c[0]==='renameVoice'&&c[1]==='session-1790766999000/voice-1'&&c[2]==='Marco'));
    state.entries.push({...state.entries[0],nativeSegmentId:'session-1790766999000/000000030000.wav#0',offsetMs:30000,endMs:31000,text:'La stessa voce ritorna'});state.chunkCount=2;run('syncNativeRecorder()');
    check('same voice in later chunks keeps the user assigned name',run('transcriptEntries.at(-1).speakerName')==='Marco'&&run('transcriptEntries.at(-1).speaker')===2);
    const count=run('transcriptEntries.length');run('syncNativeRecorder()');check('native voice refresh does not duplicate utterances',run('transcriptEntries.length')===count);
    state.active=false;state.recording=false;run("syncNativeRecorder();$('nativeVoiceModeSelect').value='manual';$('nativeVoiceModeSelect').onchange();");
    check('manual attribution remains available after capture',calls.some(c=>c[0]==='voiceMode'&&c[1]===false));
    run("transcriptEntries=[];speakers=['Persona 1'];");
    state={...state,active:true,recording:true,sessionId:'session-1790767999000',chunkCount:2,entries:[
        {nativeSegmentId:'session-1790767999000/000000000000.wav#0',nativeVoiceId:'session-1790767999000/voice-1',speaker:1,speakerName:'Voce 1',speakerSource:'local',text:"anche l'audio",offsetMs:29000,endMs:35080},
        {nativeSegmentId:'session-1790767999000/000000035000.wav#0',speaker:0,speakerName:'Non assegnato',speakerSource:'local',speakerReview:true,speakerUnassignedReason:'timing',text:'è',offsetMs:35000,endMs:35380}
    ]};run('syncNativeRecorder()');
    check('live native import renders a short boundary word inline before the continuation arrives',run('transcriptTurns().length')===1&&run('transcriptTurns()[0].text').endsWith('[Non assegnato: è]'));
    state.entries.push({...state.entries[0],nativeSegmentId:'session-1790767999000/000000035000.wav#1',text:'molto convincente.',offsetMs:35300,endMs:38000});run('syncNativeRecorder()');
    check('the next native WAV completes one sentence without duplication or reassignment',run('transcriptTurns().length')===1&&run('transcriptTurns()[0].displayText')==="anche l'audio è molto convincente."&&run('transcriptEntries.length')===3&&run('transcriptEntries[1].speaker')===0&&run('subtitleBlocks().length')===3);
    console.log(`${passed}/${passed} native UI checks passed.`);
}
main().then(() => process.exit(0)).catch(error => { console.error(error); process.exit(1); });
