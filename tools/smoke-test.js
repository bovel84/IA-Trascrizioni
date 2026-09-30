#!/usr/bin/env node
/*
 * Smoke test end-to-end di VoiceScribe: esegue davvero l'app in un DOM simulato
 * (tools/dom-stub.js) e verifica i flussi di Fase 1-2-3.
 * Uso: node tools/smoke-test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { createEnvironment } = require('./dom-stub');

const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const results = [];

function check(label, condition, extra = '') {
    const ok = !!condition;
    results.push(ok);
    console.log(`  ${ok ? 'ok   ' : 'FAIL '} ${label}${ok ? '' : '   -> ' + extra}`);
}

const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

const fakeApi = (url, options) => {
    if (/audio\/transcriptions/.test(url)) return { text: 'Ciao, questa e una prova di trascrizione.' };
    if (/chat\/completions/.test(url)) {
        const body = String((options && options.body) || '');
        if (/mappa concettuale/i.test(body)) {
            return { choices: [{ message: { content: '```json\n{"topic":"Prova","nodes":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"links":[{"from":"a","to":"b"}]}\n```' } }] };
        }
        return { choices: [{ message: { content: '{"title":"Grafico di prova","type":"bar","labels":["A","B"],"series":[{"name":"serie","data":[1,2]}]}' } }] };
    }
    if (/\/models/.test(url)) return { data: [{ id: 'whisper-large-v3-turbo' }, { id: 'openai/gpt-oss-120b' }, { id: 'openai/gpt-oss-20b' }] };
    return {};
};

async function main() {
    const watchdog = setTimeout(() => {
        console.error('\nTIMEOUT: il test non e terminato entro 60 secondi (possibile attesa infinita).');
        process.exit(2);
    }, 60000);

    const ctx = createEnvironment({ html, onFetch: fakeApi });
    const run = code => vm.runInContext(code, ctx, { filename: 'app.js' });
    const ev = expr => vm.runInContext(expr, ctx, { filename: 'eval.js' });
    const el = id => ctx.__elements.get(id);

    // --- esecuzione dei blocchi <script> dell'app ---
    const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)].map(m => m[1]);
    scripts.forEach((code, i) => {
        try { run(code); }
        catch (e) { check(`esecuzione blocco script #${i + 1}`, false, e.stack.split('\n').slice(0, 3).join(' | ')); }
    });
    check(`eseguiti ${scripts.length} blocchi script inline`, scripts.length === 4);

    try {
        await run('(async () => { await init(); })()');
        check('init() senza errori', true);
    } catch (e) {
        check('init() senza errori', false, e.stack.split('\n').slice(0, 4).join(' | '));
    }

    check('modello STT di default configurato', ev('sttModel') === 'whisper-large-v3-turbo', ev('sttModel'));
    check('modello LLM non piu deprecato', ev('llmModel') === 'openai/gpt-oss-120b', ev('llmModel'));

    // ============ FASE 2: registrazione + trascrizione incrementale ============
    run("whisperKey = 'gsk_test'; llamaKey = 'gsk_test';");
    try {
        await run('(async () => { startTime = Date.now(); await startRecording(); })()');
    } catch (e) { check('startRecording senza errori', false, e.message); }
    check('registrazione avviata', ev('isRecording') === true);

    // simulo due giri di chunk (2 minuti ciascuno)
    run('startTime = Date.now() - 130000;');
    await run('rotateChunk()');
    await wait(60);
    run('startTime = Date.now() - 260000;');
    await run('stopRecording()');
    await wait(80);

    const entries = ev('transcriptEntries');
    check('trascrizione incrementale: una voce per chunk', entries.length === 2, 'voci: ' + entries.length);
    check('timestamp diversi per chunk', entries.length === 2 && entries[0].time !== entries[1].time,
        JSON.stringify(entries.map(e => e.time)));
    check('offsetMs presente in ogni voce', entries.every(e => typeof e.offsetMs === 'number'));
    check('sessione di registrazione chiusa', ev('isRecording') === false);

    const audioChunks = await run("(async () => { const c = await getChunksFromDB('current_audio'); return c.length; })()");
    check('audio consolidato in IndexedDB (current_audio)', audioChunks === 2, 'chunk: ' + audioChunks);
    const tempChunks = await run("(async () => { const c = await getChunksFromDB('temp_recording'); return c.length; })()");
    check('nessun chunk residuo in temp_recording', tempChunks === 0);
    check('niente copia audio in RAM', ev('audioBlob') === null);

    // ============ FASE 1: escape del testo trascritto ============
    run(`addEntry('<img src=x onerror=alert(1)> & <b>grassetto</b>', 3000)`);
    const area = el('transcriptArea');
    const lastNode = area.children[area.children.length - 1];
    check('testo pericoloso escapato (niente HTML iniettato)',
        lastNode.innerHTML.includes('&lt;img src=x onerror=alert(1)&gt;') && !lastNode.innerHTML.includes('<img src=x'));

    // ============ FASE 3: ricerca nel transcript ============
    el('transcriptSearch').value = 'prova';
    run('refreshTranscript()');
    check('ricerca filtra e conteggia', /visibili/.test(el('transcriptCount').textContent), el('transcriptCount').textContent);
    check('evidenzia i risultati', el('transcriptArea').children.some(c => c.innerHTML.includes('<mark>')));
    el('transcriptSearch').value = 'nessun-risultato-xyz';
    run('refreshTranscript()');
    check('stato vuoto per ricerca senza esiti', el('transcriptArea').children.some(c => c.innerHTML.includes('Nessun risultato')));
    el('transcriptSearch').value = '';
    run('refreshTranscript()');

    // ============ FASE 3: azioni sulle frasi ============
    const speakersBefore = ev('speakers[0]');
    run('cycleSpeaker(0)');
    check('cambio speaker con un tocco', ev('transcriptEntries[0].speakerName') !== speakersBefore,
        ev('transcriptEntries[0].speakerName'));
    const countBefore = ev('transcriptEntries.length');
    run('deleteEntry(0)');
    check('eliminazione frase', ev('transcriptEntries.length') === countBefore - 1);
    el('transcriptSearch').value = '';
    run('refreshTranscript()');
    // ============ FASE 3: export SRT / VTT / Markdown ============
    const captured = [];
    ctx.URL = { createObjectURL: blob => { captured.push(blob); return 'blob:stub'; }, revokeObjectURL() {} };
    run('exportSRT()');
    await wait(20);
    check('export SRT produce un file', captured.length === 1, 'file: ' + captured.length);
    const srt = captured[0] ? await captured[0].text() : '';
    check('SRT con numerazione e tempi', /^1\r?\n\d{2}:\d{2}:\d{2},\d{3} --> \d{2}:\d{2}:\d{2},\d{3}\r?\n/.test(srt),
        JSON.stringify(srt.slice(0, 70)));
    run('exportVTT()');
    run('exportMarkdown()');
    await wait(20);
    check('export VTT + Markdown', captured.length === 3, 'file: ' + captured.length);
    const vtt = captured[1] ? await captured[1].text() : '';
    check('VTT con intestazione e speaker', /^WEBVTT/.test(vtt) && vtt.includes('<v '), JSON.stringify(vtt.slice(0, 50)));
    const md = captured[2] ? await captured[2].text() : '';
    check('Markdown con sezioni', /# VoiceScribe/.test(md) && /## Trascrizione/.test(md), JSON.stringify(md.slice(0, 60)));

    // ============ AI: modello configurabile + JSON robusto ============
    run("notesText = 'Nota di prova';");
    await run('runAI("summary")');
    const aiBox = el('aiResult');
    check('AI riassunto mostrato (con textContent)', aiBox.children.length === 2 && aiBox.children[1].textContent.length > 0);
    await run('runAI("chart")');
    check('AI grafico normalizzato', ev('chartData && chartData.labels && chartData.labels.length') === 2, JSON.stringify(ev('chartData')));
    await run('runAI("concept")');
    check('AI mappa estratta dal blocco ```json', ev('conceptMapData && conceptMapData.nodes.length') === 2);
    const chatCalls = ctx.__calls.fetch.filter(c => /chat\/completions/.test(c.url));
    check('chat usa il modello configurato', chatCalls.length > 0 && chatCalls.every(c => String(c.options.body).includes('openai/gpt-oss-120b')));
    check('modello deprecato mai chiamato', !ctx.__calls.fetch.some(c => /llama-3\.1-8b-instant/.test(String(c.options.body || ''))));
    check('trascrizione usa il modello STT configurato',
        ctx.__calls.fetch.filter(c => /audio\/transcriptions/.test(c.url)).length > 0);

    // ============ FASE 3: salvataggio / ricarica progetto ============
    el('sessionNameInput').value = 'Riunione <test>';
    await run('confirmSaveSession()');
    const saved = ev('savedRecordings');
    check('progetto salvato', saved.length === 1, 'progetti: ' + saved.length);
    check('autosave azzerata dopo il salvataggio', ctx.__store.get('voicescribe_autosave') === undefined);
    run('renderSavedRecordings()');
    check('nome progetto escapato nella lista', el('recordingsList').innerHTML.includes('Riunione &lt;test&gt;'));
    check('separatore data corretto (nessun "?")', !/\} \? \$\{/.test(el('recordingsList').innerHTML));

    await run('newTranscript()');
    check('nuova sessione azzera il transcript', ev('transcriptEntries.length') === 0);
    check('nuova sessione azzera audio e bozza', ev('audioBlob') === null && ctx.__store.get('voicescribe_autosave') === undefined);
    await run(`loadRec(${saved[0].id})`);
    check('progetto ricaricato con tutte le frasi', ev('transcriptEntries.length') === saved[0].entries.length);
    check('audio del progetto ricaricato da IndexedDB', ev('!!audioBlob') === true);

    // ============ FASE 3: autosave e ripristino bozza ============
    run("addEntry('frase per la bozza', 1000); autosaveNow();");
    check('bozza salvata in localStorage', !!ctx.__store.get('voicescribe_autosave'));
    const draft = JSON.parse(ctx.__store.get('voicescribe_autosave'));
    check('bozza contiene le frasi', draft.entries.length === ev('transcriptEntries.length'), 'bozza: ' + draft.entries.length);
    run("transcriptEntries = []; notesText = ''; restoreSnapshot(JSON.parse(localStorage.getItem(AUTOSAVE_KEY)))");
    check('bozza ripristinata correttamente', ev('transcriptEntries.length') === draft.entries.length);

    // ============ localStorage pieno: salvataggio di riserva ============
    const originalSet = ctx.localStorage.setItem;
    let thrown = false;
    ctx.localStorage.setItem = (key, value) => {
        if (key === 'voicescribe_recordings' && !thrown) { thrown = true; throw new Error('QuotaExceededError'); }
        originalSet(key, value);
    };
    el('sessionNameInput').value = 'Progetto senza immagini';
    await run('confirmSaveSession()');
    ctx.localStorage.setItem = originalSet;
    check('salvataggio di riserva senza immagini',
        ev('savedRecordings.length') === 2 && ev('savedRecordings[savedRecordings.length - 1].hasChart') === false,
        'progetti: ' + ev('savedRecordings.length'));

    // ============ funzioni di utilità ============
    check('escapeHtml', ev('escapeHtml(\'<a>"&\')') === '&lt;a&gt;&quot;&amp;');
    check('formatDuration 1h', ev('formatDuration(3661000)') === '1:01:01');
    check('formatDuration 90s', ev('formatDuration(90000)') === '01:30');
    check('parseDuration inverso', ev("parseDuration('01:30')") === 90000);
    check('extractJSON con testo di contorno', ev('extractJSON("ecco: {\\"a\\":1} fine").a') === 1);
    check('extractJSON da blocco ```json', ev('extractJSON("```json\\n{\\"b\\":2}\\n```").b') === 2);
    check('srtTime', ev('srtTime(3661500)') === '01:01:01,500');
    check('retry con backoff attivo', /attempt < 2/.test(String(ev('transcribeBlob.toString()'))));
    check('beforeunload registrato', /beforeunload/.test(String(ev('setupLifecycleGuards.toString()'))));

    clearTimeout(watchdog);
}

main().then(() => {
    const failed = results.filter(ok => !ok).length;
    console.log(`\n${results.length - failed}/${results.length} controlli superati.`);
    process.exit(failed ? 1 : 0);
}).catch(e => {
    console.error('Errore non gestito nel test:', e);
    process.exit(1);
});
