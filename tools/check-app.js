#!/usr/bin/env node
/*
 * Validatore per VoiceScribe (app single-file).
 * Uso: node tools/check-app.js [file.html]
 *
 * Controlla:
 *  1. sintassi di ogni blocco <script> inline (senza esecuzione: new Function)
 *  2. riferimenti $('id') / getElementById('id') che non hanno un id="..." corrispondente
 *  3. invarianti del progetto (marker delle feature) e residui noti
 */
const fs = require('fs');
const path = require('path');

const target = process.argv[2] || 'index.html';
const file = path.resolve(__dirname, '..', target);
const html = fs.readFileSync(file, 'utf8');
const lines = html.split(/\r?\n/);
const problems = [];
const ok = [];

// ---------- 1) sintassi script inline ----------
const scriptRe = /<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi;
let m, count = 0;
while ((m = scriptRe.exec(html))) {
    count++;
    const code = m[1];
    const startLine = html.slice(0, m.index).split('\n').length;
    const lineCount = code.split('\n').length;
    try {
        new Function(code);
        ok.push(`script inline #${count} (riga ${startLine}, ${lineCount} righe)`);
    } catch (e) {
        problems.push(`SINTASSI -> script #${count} (riga ${startLine}): ${e.message}`);
    }
}
if (!count) problems.push('SINTASSI -> nessun blocco <script> inline trovato');

// ---------- 2) id referenziati vs definiti ----------
const usedIds = new Set();
for (const re of [/\$\('([A-Za-z0-9_-]+)'\)/g, /getElementById\('([A-Za-z0-9_-]+)'\)/g, /\$\("([A-Za-z0-9_-]+)"\)/g]) {
    let x;
    while ((x = re.exec(html))) usedIds.add(x[1]);
}
const definedIds = new Set();
{
    let x;
    const idRe = /id="([^"]+)"/g;
    while ((x = idRe.exec(html))) definedIds.add(x[1]);
}
const dynamicIds = new Set(['emptyState']); // creato a runtime
const missing = [...usedIds].filter(id => !definedIds.has(id) && !dynamicIds.has(id));
if (missing.length) problems.push('IDS -> riferimenti senza elemento: ' + missing.join(', '));
ok.push(`${usedIds.size} id referenziati, ${definedIds.size} id definiti`);

// ---------- 3) invarianti / residui ----------
const checks = [
    ['fix canvas: setTransform usato, ctx.scale assente', /ctx\.setTransform\(/.test(html) && !/ctx\.scale\(/.test(html)],
    ['fase1: escape HTML presente', /function escapeHtml\(/.test(html)],
    ['fase1: modelli configurabili (stt/llm)', /sttModel/.test(html) && /llmModel/.test(html)],
    ['fase1: beforeunload attivo', /beforeunload/.test(html)],
    ['fase1: nessun "?" perso nelle stringhe', !/showToast\('\?/.test(html) && !/\} \? \$\{/.test(html)],
    ['fase1: modello LLM obsoleto rimosso', !/llama-3\.1-8b-instant/.test(html)],
    ['fase2: trascrizione incrementale (coda)', /processLiveQueue/.test(html) && /enqueueChunk/.test(html)],
    ['fase2: audio su IndexedDB (niente doppia RAM)', /moveChunksSession/.test(html) && !/recordedChunks/.test(html)],
    ['fase3: ricerca nel transcript', /transcriptSearch/.test(html)],
    ['fase3: modifica/elimina voce', /window\.editEntry/.test(html) && /window\.deleteEntry/.test(html)],
    ['fase3: export SRT/VTT/MD', /window\.exportSRT/.test(html) && /window\.exportVTT/.test(html) && /window\.exportMarkdown/.test(html)],
    ['fase3: autosave', /autosaveNow/.test(html)],
    ['fase3: PWA (manifest + service worker)', /manifest\.webmanifest/.test(html) && /serviceWorker/.test(html)],
    ['fase2: retry/timeout sulle chiamate API', /fetchWithTimeout/.test(html)],
    ['fase2: timestamp per chunk (offsetMs)', /offsetMs/.test(html)],
];
for (const [label, pass] of checks) {
    if (pass) ok.push(label);
    else problems.push('CHECK -> manca: ' + label);
}

// ---------- report ----------
console.log(`\n=== check-app: ${path.basename(file)} (${lines.length} righe, ${(html.length / 1024).toFixed(1)} KB) ===\n`);
ok.forEach(o => console.log('  ok    ' + o));
if (problems.length) {
    console.log('');
    problems.forEach(p => console.log('  FAIL  ' + p));
    console.log(`\n${problems.length} problema/i trovato/i.\n`);
    process.exit(1);
}
console.log('\nTutti i controlli superati.\n');
