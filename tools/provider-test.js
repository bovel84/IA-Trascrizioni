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
        if (/\/api\/tags$/.test(url)) return { models: [{ name: 'new/chat-model' }] };
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
        await run(`$('aiProviderSelect').value = '${provider}'; $('aiProviderSelect').onchange()`);
        if (provider !== 'groq') check(run('llamaKey') === '', provider + ' never inherits another key');
        await run(`$('llamaKeyInput').value = 'test_${provider}'; $('saveKeysBtn').onclick()`);
        check(calls.at(-1).url === (provider === 'ollama' ? 'https://ollama.com/api/tags' : base + '/models'), provider + ' saving key refreshes models automatically');
        check(run('llamaKey') === 'test_' + provider, provider + ' accepts its key format');
        run(`$('customLlmModel').value = 'custom/${provider}'; $('customLlmModel').onchange()`);
        await run("groqChat({messages:[{role:'user',content:'Prova'}]})");
        const call = calls.at(-1);
        check(call.url === base + '/chat/completions', provider + ' routes to its own endpoint');
        check(call.options.headers.Authorization === 'Bearer test_' + provider, provider + ' sends only its own key');
        check(JSON.parse(call.options.body).model === 'custom/' + provider, provider + ' sends selected model');
        await run('refreshAvailableModels()');
        check(calls.at(-1).url === (provider === 'ollama' ? 'https://ollama.com/api/tags' : base + '/models'), provider + ' refreshes its model list');
        if (provider !== 'groq') check(run('sttModel') === 'whisper-large-v3', provider + ' does not change Whisper model');
        check(run('whisperKey') === 'gsk_audio', provider + ' does not change recording key');
    }
    for (const provider of Object.keys(bases)) {
        await run(`$('aiProviderSelect').value = '${provider}'; $('aiProviderSelect').onchange()`);
        check(run('llamaKey') === 'test_' + provider && run('llmModel') === 'new/chat-model', provider + ' restores separate settings');
        check(run('readModelCatalog(aiProvider).includes("new/chat-model")'), provider + ' retains refreshed catalog');
    }
    // Race and 400 fallback: switch UI while first request is in flight.
    const attempts = [];
    ctx.nativeAiRequest = async (provider, path, key, body) => {
        if (path === '/models') return { ok: true, status: 200, json: async () => provider === 'ollama' ? { models: [{ name: 'new/chat-model' }] } : { data: [{ id: 'new/chat-model' }] } };
        attempts.push({ provider, key, body });
        if (attempts.length === 1) {
            await run("$('aiProviderSelect').value = 'ollama'; $('aiProviderSelect').onchange()");
            return { ok: false, status: 400, json: async () => ({ error: { message: 'unsupported response_format' } }) };
        }
        return { ok: true, status: 200, json: async () => ({ choices: [] }) };
    };
    await run("$('aiProviderSelect').value = 'nvidia'; $('aiProviderSelect').onchange()");
    await run("groqChat({messages:[],jsonMode:true})");
    check(attempts.length === 2 && attempts.every(a => a.provider === 'nvidia' && a.key === 'test_nvidia'), 'in-flight retries keep original provider and credential');
    check(!attempts[1].body.response_format, 'JSON mode falls back for unsupported models');
    ctx.nativeAiRequest = async () => ({ ok: false, status: 401, json: async () => ({ error: 'Invalid key' }) });
    await assert.rejects(run('groqChat({messages:[]})'), /Invalid key/);
    check(true, 'authorization errors displayed without trying another provider');
    delete ctx.nativeAiRequest;
    ctx.AndroidRecorder = { aiRequest: (id, provider, endpoint, key, body) => {
        if (endpoint === '/models') { ctx.nativeAiReply(id, 200, '{"data":[{"id":"new/chat-model"}]}', ''); return; }
        check(provider === 'openrouter' && endpoint === '/chat/completions' && key === 'test_openrouter', 'APK forwards request to the selected native provider');
        ctx.nativeAiReply(id, 200, JSON.stringify({ choices: [] }), '');
    } };
    run(fs.readFileSync(path.join(__dirname, '..', 'native-bridge.js'), 'utf8'));
    await run("$('aiProviderSelect').value = 'openrouter'; $('aiProviderSelect').onchange()");
    await run('groqChat({messages:[]})');
    ctx.AndroidRecorder.aiRequest = id => ctx.nativeAiReply(id, 0, '', 'Offline');
    await assert.rejects(run("nativeAiRequest('ollama','/models','test')"), /Offline/);
    check(true, 'APK network failure rejects the request and cleans up');
    ctx.AndroidRecorder.aiRequest = id => ctx.nativeAiReply(id, 429, '{"error":{"message":"Quota"}}', '');
    await assert.rejects(run('groqChat({messages:[]})'), /Quota/);
    check(true, 'APK quota errors reach the UI');
    ctx.AndroidRecorder.aiRequest = id => ctx.nativeAiReply(id, 403, '{"error":"Model requires a subscription"}', '');
    await run('refreshAvailableModels()');
    check(ctx.__elements.get('aiProviderStatus').textContent.includes('HTTP 403') && ctx.__elements.get('aiProviderStatus').textContent.includes('subscription'), 'catalog 403 stays visible with provider explanation');
    await assert.rejects(run('groqChat({messages:[]})'), /HTTP 403.*subscription/);
    check(!ctx.__elements.get('aiProviderStatus').textContent.includes('test_openrouter'), 'errors do not expose the saved key');
    const redacted = run("providerError('openrouter',403,{error:'Denied test_openrouter'},'test_openrouter')");
    check(!redacted.includes('test_openrouter'), 'credential echoed by provider is redacted');
    check(run("providerError('ollama',403,null,'test')").includes('HTTP 403'), 'empty forbidden response still has an actionable message');
    // Older refreshes cannot overwrite a newer key, catalog or status.
    let finishOld;
    ctx.nativeAiRequest = () => new Promise(resolve => { finishOld = resolve; });
    const stale = run('refreshAvailableModels()');
    ctx.nativeAiRequest = async () => ({ ok: true, status: 200, json: async () => ({ data: [{ id: 'fresh/model' }] }) });
    await run("$('llamaKeyInput').value = 'new_key'; $('saveKeysBtn').onclick()");
    finishOld({ ok: false, status: 403, json: async () => ({ error: 'old forbidden' }) });
    await stale;
    check(run('llmModel') === 'fresh/model' && !ctx.__elements.get('aiProviderStatus').textContent.includes('403'), 'stale response cannot overwrite new key refresh');
    console.log(count + ' provider checks passed.');
    process.exit(0);
}
main().catch(e => { console.error(e); process.exit(1); });
