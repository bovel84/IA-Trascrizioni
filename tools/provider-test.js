const fs = require('fs');
const vm = require('vm');
const path = require('path');
const assert = require('assert');
const { createEnvironment } = require('./dom-stub');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
let count = 0;
function check(value, label) { assert(value, label); count++; console.log('ok  ' + label); }
async function main() {
    const calls = [];
    const ctx = createEnvironment({ html, onFetch: (url, options) => {
        calls.push({ url, options });
        return /\/models$/.test(url) ? { data: [{ id: 'new/chat-model' }, { id: 'whisper-large-v3' }] } : { choices: [{ message: { content: 'Risposta di prova' } }] };
    } });
    ctx.localStorage.setItem('groq_llama_key', 'gsk_existing');
    ctx.localStorage.setItem('groq_llm_model', 'openai/gpt-oss-20b');
    const run = code => vm.runInContext(code, ctx);
    for (const match of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi)) run(match[1]);
    await run('init()');
    check(run('llamaKey') === 'gsk_existing' && run('llmModel') === 'openai/gpt-oss-20b', 'existing Groq key and model retained');
    run("whisperKey = 'gsk_audio'");
    const bases = { groq: 'https://api.groq.com/openai/v1', ollama: 'https://ollama.com/v1', nvidia: 'https://integrate.api.nvidia.com/v1', openrouter: 'https://openrouter.ai/api/v1' };
    for (const [provider, base] of Object.entries(bases)) {
        run(`$('aiProviderSelect').value = '${provider}'; $('aiProviderSelect').onchange()`);
        if (provider !== 'groq') check(run('llamaKey') === '', provider + ' never inherits another key');
        run(`$('llamaKeyInput').value = 'test_${provider}'; $('saveKeysBtn').onclick()`);
        check(run('llamaKey') === 'test_' + provider, provider + ' accepts its key format');
        run(`$('customLlmModel').value = 'custom/${provider}'; $('customLlmModel').onchange()`);
        await run("groqChat({messages:[{role:'user',content:'Prova'}]})");
        const call = calls.at(-1);
        check(call.url === base + '/chat/completions', provider + ' routes to its own endpoint');
        check(call.options.headers.Authorization === 'Bearer test_' + provider, provider + ' sends only its own key');
        check(JSON.parse(call.options.body).model === 'custom/' + provider, provider + ' sends selected model');
        await run('refreshAvailableModels()');
        check(calls.at(-1).url === base + '/models', provider + ' refreshes its model list');
        if (provider !== 'groq') check(run('sttModel') === 'whisper-large-v3', provider + ' does not change Whisper model');
        check(run('whisperKey') === 'gsk_audio', provider + ' does not change recording key');
    }
    for (const provider of Object.keys(bases)) {
        run(`$('aiProviderSelect').value = '${provider}'; $('aiProviderSelect').onchange()`);
        check(run('llamaKey') === 'test_' + provider && run('llmModel') === 'new/chat-model', provider + ' restores separate settings');
    }
    // Race and 400 fallback: switch UI while first request is in flight.
    const attempts = [];
    ctx.nativeAiRequest = async (provider, path, key, body) => {
        attempts.push({ provider, key, body });
        if (attempts.length === 1) {
            run("$('aiProviderSelect').value = 'ollama'; $('aiProviderSelect').onchange()");
            return { ok: false, status: 400, json: async () => ({ error: { message: 'unsupported response_format' } }) };
        }
        return { ok: true, status: 200, json: async () => ({ choices: [] }) };
    };
    run("$('aiProviderSelect').value = 'nvidia'; $('aiProviderSelect').onchange()");
    await run("groqChat({messages:[],jsonMode:true})");
    check(attempts.length === 2 && attempts.every(a => a.provider === 'nvidia' && a.key === 'test_nvidia'), 'in-flight retries keep original provider and credential');
    check(!attempts[1].body.response_format, 'JSON mode falls back for unsupported models');
    ctx.nativeAiRequest = async () => ({ ok: false, status: 401, json: async () => ({ error: 'Invalid key' }) });
    await assert.rejects(run('groqChat({messages:[]})'), /Invalid key/);
    check(true, 'authorization errors displayed without trying another provider');
    delete ctx.nativeAiRequest;
    ctx.AndroidRecorder = { aiRequest: (id, provider, endpoint, key, body) => {
        check(provider === 'openrouter' && endpoint === '/chat/completions' && key === 'test_openrouter', 'APK forwards request to the selected native provider');
        ctx.nativeAiReply(id, 200, JSON.stringify({ choices: [] }), '');
    } };
    run(fs.readFileSync(path.join(__dirname, '..', 'native-bridge.js'), 'utf8'));
    run("$('aiProviderSelect').value = 'openrouter'; $('aiProviderSelect').onchange()");
    await run('groqChat({messages:[]})');
    ctx.AndroidRecorder.aiRequest = id => ctx.nativeAiReply(id, 0, '', 'Offline');
    await assert.rejects(run("nativeAiRequest('ollama','/models','test')"), /Offline/);
    check(true, 'APK network failure rejects the request and cleans up');
    ctx.AndroidRecorder.aiRequest = id => ctx.nativeAiReply(id, 429, '{"error":{"message":"Quota"}}', '');
    await assert.rejects(run('groqChat({messages:[]})'), /Quota/);
    check(true, 'APK quota errors reach the UI');
    console.log(count + ' provider checks passed.');
    process.exit(0);
}
main().catch(e => { console.error(e); process.exit(1); });
