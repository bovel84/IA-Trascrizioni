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
        entries: [{ nativeSegmentId: 'session-1790766000000/000000000000.wav', offsetMs: 0, text: 'Prima frase' }] };
    run('syncNativeRecorder()');
    check('original recording button reflects native state', ctx.__elements.get('recordBtn').classList.contains('recording'));
    check('native transcript appears in the original transcript panel', run('transcriptEntries.length') === 1 && run('transcriptEntries[0].text') === 'Prima frase');
    check('timer uses captured audio duration', ctx.__elements.get('timer').textContent === '02:00');
    run('syncNativeRecorder(); syncNativeRecorder();');
    check('repeated refresh does not duplicate transcript entries', run('transcriptEntries.length') === 1);
    run("transcriptEntries[0].text = 'Testo corretto'; syncNativeRecorder();");
    check('native refresh preserves user edits', run('transcriptEntries[0].text') === 'Testo corretto');
    run('togglePause()');
    check('Pause uses the native recorder', calls.some(c => c[0] === 'pause' && c[1] === true));
    run("document.visibilityState = 'hidden';");
    await run('handleVisibilityChange()');
    check('hidden WebView does not stop native recording', !calls.some(c => c[0] === 'stop') && run('isRecording'));
    state.entries.push({ nativeSegmentId: 'session-1790766000000/000000120000.wav', offsetMs: 120000, text: 'Frase pronunciata a schermo bloccato' });
    state.elapsedMs = 240000; state.chunkCount = 2;
    run("document.visibilityState = 'visible'; syncNativeRecorder();");
    check('return from background imports missed segments with offsets', run('transcriptEntries.length') === 2 && run('transcriptEntries[1].offsetMs') === 120000);
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
    await run("$('audioFileInput').onchange({ target: $('audioFileInput') })");
    check('imported audio detaches the previous native audio reference', ctx.nativeAudioSession === null);
    run("nativeRecorderError('Permesso microfono negato')");
    check('denied permission leaves controls usable', !run('isProcessing'));
    run("const detachedPdf = new HTMLAnchorElement(); detachedPdf.download = 'documento.pdf'; detachedPdf.href = 'blob:test-pdf'; detachedPdf.dispatchEvent({ type: 'click' });");
    for (let i = 0; i < 20 && !calls.some(c => c[0] === 'download'); i++) await new Promise(resolve => setTimeout(resolve, 10));
    check('jsPDF detached synthetic download reaches Android save dialog with the file bytes', calls.some(c => c[0] === 'download' && c[1] === 'documento.pdf' && c[2] === 'application/pdf' && c[3] === 'UERG'));
    console.log(`${passed}/${passed} native UI checks passed.`);
}
main().then(() => process.exit(0)).catch(error => { console.error(error); process.exit(1); });
