#!/usr/bin/env node
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert/strict');
const { createEnvironment } = require('./dom-stub');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
let checks = 0;
function check(label, value) { assert.ok(value, label); checks++; console.log('ok  ' + label); }
function environment(previous) {
    const ctx = createEnvironment({ html });
    if (previous) {
        ctx.indexedDB = previous.indexedDB;
        for (const [key, value] of previous.__store) ctx.localStorage.setItem(key, value);
    }
    const run = code => vm.runInContext(code, ctx);
    [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].forEach(m => run(m[1]));
    return { ctx, run };
}
async function drain(run) {
    await run('lastChunkSaved');
    for (let i = 0; i < 1000; i++) {
        if (!run('liveProcessing')) return;
        await new Promise(resolve => setImmediate(resolve));
    }
    throw Error('Transcription queue did not finish');
}
async function recordTwoHours(run, beforeStop = () => {}) {
    await run('startRecording()');
    assert.equal(run('isRecording'), true);
    for (let i = 1; i < 60; i++) {
        run(`startTime = Date.now() - ${i * 120000}`);
        await run('rotateChunk()'); await drain(run);
    }
    beforeStop();
    run('startTime = Date.now() - 7200000');
    await run('stopRecording()');
}
async function main() {
    const normal = environment();
    await normal.run('init()'); normal.run('whisperKey = "test_key"; localStorage.setItem("groq_whisper_key", whisperKey)');
    let uploaded = 0;
    normal.ctx.fetch = async () => ({ ok: true, status: 200, json: async () => ({ text: `Frase ${++uploaded}` }) });
    await recordTwoHours(normal.run);
    check('two-hour recording produces all 60 chronological transcripts', uploaded === 60 && normal.run('transcriptEntries.length') === 60 && normal.run('transcriptEntries.at(-1).offsetMs') >= 7080000);
    const metadata = await normal.run('listChunkMetadata(AUDIO_SESSION)');
    check('every audio segment has a durable transcript checkpoint', metadata.length === 60 && metadata.every(c => c.transcriptionDone && c.transcriptionText && !c.chunk));
    check('two-hour completion releases controls and displays final progress', !normal.run('isProcessing || isRecording') && /60\/60/.test(normal.ctx.__elements.get('transcriptionStatus').textContent));

    const broken = environment();
    await broken.run('init()'); broken.run('whisperKey = "test_key"; localStorage.setItem("groq_whisper_key", whisperKey)');
    let attempted = 0;
    broken.ctx.fetch = async () => ++attempted === 5
        ? { ok: false, status: 401, json: async () => ({ error: { message: 'Chiave test_key non valida' } }) }
        : { ok: true, status: 200, json: async () => ({ text: `Testo ${attempted}` }) };
    await recordTwoHours(broken.run, () => {
        check('offline backlog contains IDs instead of audio blobs', broken.run('liveQueue.length > 0 && [...liveQueue, ...failedChunks].every(c => c.id != null && !c.blob && !c.chunk)'));
    });
    check('provider failure stops repeated uploads across the remaining two hours', attempted === 5);
    check('partial failure preserves all audio and accurately reports missing segments', (await broken.run('listChunkMetadata(AUDIO_SESSION)')).length === 60 && /4\/60/.test(broken.ctx.__elements.get('transcriptionStatus').textContent));
    check('provider errors do not expose the API key', !broken.ctx.__elements.get('transcriptionStatus').textContent.includes('test_key'));
    await broken.run('startRecording()');
    check('starting another recording cannot overwrite unfinished audio', !broken.run('isRecording') && (await broken.run('listChunkMetadata(AUDIO_SESSION)')).length === 60);
    broken.run('transcriptEntries[0].text = "Correzione manuale"; deleteEntry(1); autosaveNow();');

    // New JS context and localStorage draft, same persistent IndexedDB: an app restart.
    const resumed = environment(broken.ctx);
    await resumed.run('init()');
    let recovered = 0;
    resumed.ctx.fetch = async () => ({ ok: true, status: 200, json: async () => ({ text: `Recuperata ${++recovered}` }) });
    await resumed.run('retryPendingTranscription()');
    check('restart retries only the 56 missing segments', recovered === 56);
    check('resume preserves manual corrections and deletions without duplicates', resumed.run('transcriptEntries.length') === 59 && resumed.run('transcriptEntries[0].text') === 'Correzione manuale');
    await resumed.run('retryPendingTranscription()');
    check('retry after completion makes no further provider requests', recovered === 56 && resumed.run('transcriptEntries.length') === 59);

    const quota = environment(); await quota.run('init()'); quota.run('whisperKey = "test_key"; localStorage.setItem("groq_whisper_key", whisperKey)');
    let quotaCalls = 0;
    quota.ctx.fetch = async () => { quotaCalls++; return { ok: false, status: 429, headers: { get: () => '3600' }, json: async () => ({}) }; };
    await quota.run('saveChunkToDB(new Blob(["audio"]), REC_SESSION, { offsetMs: 0 })');
    await quota.run('(async () => { await processRecoveredChunks(await listChunkMetadata(REC_SESSION)); })()');
    check('long Retry-After pauses immediately while preserving the pending segment', quotaCalls === 1 && !(await quota.run('listChunkMetadata(AUDIO_SESSION)'))[0].transcriptionDone && /3600/.test(quota.ctx.__elements.get('transcriptionStatus').textContent));

    const malformed = environment(); await malformed.run('init()'); malformed.run('whisperKey = "test_key"; localStorage.setItem("groq_whisper_key", whisperKey)');
    malformed.ctx.fetch = async () => ({ ok: true, status: 200, json: async () => ({}) });
    await malformed.run('saveChunkToDB(new Blob(["audio"]), REC_SESSION)');
    await malformed.run('(async () => { await processRecoveredChunks(await listChunkMetadata(REC_SESSION)); })()');
    check('HTTP 200 without text remains pending instead of reporting success', !(await malformed.run('listChunkMetadata(AUDIO_SESSION)'))[0].transcriptionDone && /0\/1/.test(malformed.ctx.__elements.get('transcriptionStatus').textContent));

    const silence = environment(); await silence.run('init()'); silence.run('whisperKey = "test_key"; localStorage.setItem("groq_whisper_key", whisperKey)');
    silence.ctx.fetch = async () => ({ ok: true, status: 200, json: async () => ({ text: '' }) });
    await silence.run('saveChunkToDB(new Blob(["audio"]), REC_SESSION)');
    await silence.run('(async () => { await processRecoveredChunks(await listChunkMetadata(REC_SESSION)); })()');
    check('silence is checkpointed with an explicit no-speech result', (await silence.run('listChunkMetadata(AUDIO_SESSION)'))[0].transcriptionDone && /nessun parlato/.test(silence.ctx.__elements.get('transcriptionStatus').textContent));

    const stalled = environment(); await stalled.run('init()'); stalled.run('whisperKey = "test_key"; sleep = async () => {};');
    let stalledCalls = 0;
    stalled.ctx.setTimeout = (fn, ms) => setTimeout(fn, ms === 90000 ? 5 : ms);
    stalled.ctx.fetch = async () => { stalledCalls++; return { ok: true, status: 200, json: () => new Promise(() => {}) }; };
    await stalled.run('saveChunkToDB(new Blob(["audio"]), REC_SESSION)');
    await stalled.run('(async () => { await processRecoveredChunks(await listChunkMetadata(REC_SESSION)); })()');
    check('a response body that never arrives cannot hang completion', stalledCalls === 3 && !stalled.run('isProcessing') && /timeout/.test(stalled.ctx.__elements.get('transcriptionStatus').textContent));

    const disk = environment(); await disk.run('init()'); disk.run('whisperKey = "test_key"; localStorage.setItem("groq_whisper_key", whisperKey)');
    disk.ctx.fetch = async () => ({ ok: true, status: 200, json: async () => ({ text: 'Testo' }) });
    await disk.run('saveChunkToDB(new Blob(["audio"]), REC_SESSION)');
    disk.ctx.__db.stores.get('audioChunks').put = () => { throw Error('Spazio esaurito'); };
    await disk.run('(async () => { await processRecoveredChunks(await listChunkMetadata(REC_SESSION)); })()');
    check('failed checkpoint commits preserve pending audio and release the UI', !(await disk.run('listChunkMetadata(AUDIO_SESSION)'))[0].transcriptionDone && !disk.run('isProcessing'));
    let emptyDownloads=0;
    disk.ctx.URL={createObjectURL:()=>{emptyDownloads++;return 'blob:empty';},revokeObjectURL(){}};
    disk.run('audioBlob=new Blob([],{type:"audio/wav"})');await disk.run('exportAudio()');
    check('web export rejects an empty audio blob before creating a download',emptyDownloads===0&&/vuoto/.test(disk.ctx.__elements.get('toast').textContent));
    console.log(`${checks}/${checks} long-recording checks passed.`);
}
main().then(() => process.exit(0)).catch(e => { console.error(e); process.exit(1); });
