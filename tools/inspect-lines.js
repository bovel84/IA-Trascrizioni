#!/usr/bin/env node
/*
 * Ispeziona righe "difficili" di index.html mostrandole con un alfabeto
 * non ambiguo:  backslash -> ^   virgoletta doppia -> @   apostrofo -> #
 * Uso: node tools/inspect-lines.js 1840 1880 [file]
 */
const fs = require('fs');
const args = process.argv.slice(2).map(a => parseInt(a, 10));
const from = args[0], to = args[1];
const target = process.argv[4] || process.argv[3] || 'index.html';
const file = target.endsWith('.html') ? target : args[2];
const lines = fs.readFileSync(file, 'utf8').split('\n');
for (let i = from; i <= to; i++) {
    const l = lines[i - 1];
    if (l === undefined) break;
    const safe = l.replace(/\\/g, '^').replace(/"/g, '@').replace(/'/g, '#');
    console.log(String(i).padStart(5) + ' |' + safe);
}
