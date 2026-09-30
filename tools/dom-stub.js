/*
 * Ambiente minimo (DOM / IndexedDB / API browser) per eseguire VoiceScribe in Node.
 * Serve solo per i test automatici: vedi tools/smoke-test.js
 */
const { createContext } = require('vm');

const CREATED = [];
const ELEMENTS = new Map();
let canvasContext;

class ClassList {
    constructor() { this.set = new Set(); }
    add(...names) { names.forEach(n => this.set.add(n)); }
    remove(...names) { names.forEach(n => this.set.delete(n)); }
    toggle(name, force) {
        const on = force === undefined ? !this.set.has(name) : !!force;
        if (on) this.set.add(name); else this.set.delete(name);
        return on;
    }
    contains(name) { return this.set.has(name); }
    get value() { return [...this.set].join(' '); }
}

function makeContext2D() {
    const noop = () => {};
    return {
        canvas: null,
        beginPath: noop, moveTo: noop, lineTo: noop, stroke: noop, fill: noop, rect: noop, ellipse: noop,
        fillRect: noop, clearRect: noop, drawImage: noop, setTransform: noop, scale: noop, translate: noop,
        getImageData: () => ({ data: [] }), putImageData: noop, save: noop, restore: noop,
        toDataURL: () => 'data:image/png;base64,AAAA',
        strokeStyle: '', fillStyle: '', lineWidth: 1, lineCap: '', lineJoin: ''
    };
}

class El {
    constructor(tag = 'div', id = '') {
        this.tagName = String(tag).toUpperCase();
        this.id = id;
        this.children = [];
        this.parentNode = null;
        this.style = {};
        this.dataset = {};
        this.classList = new ClassList();
        this.value = '';
        this.textContent = '';
        this.innerHTML = '';
        this.disabled = false;
        this.files = [];
        this.options = [];
        this.selectedIndex = 0;
        this.scrollTop = 0;
        this.scrollHeight = 0;
        this.width = 300;
        this.height = 150;
        this._events = {};
        CREATED.push(this);
    }
    get innerHTML() { return this._html || ''; }
    set innerHTML(value) { this._html = String(value); this.children = []; }  // come nel DOM reale
    appendChild(child) { this.children.push(child); child.parentNode = this; return child; }
    removeChild(child) { this.children = this.children.filter(c => c !== child); }
    remove() { if (this.parentNode) this.parentNode.removeChild(this); }
    addEventListener(type, fn) { (this._events[type] = this._events[type] || []).push(fn); }
    removeEventListener(type, fn) { this._events[type] = (this._events[type] || []).filter(f => f !== fn); }
    dispatch(type, event = {}) { (this._events[type] || []).forEach(fn => fn(event)); }
    querySelectorAll() { return []; }
    querySelector() { return null; }
    getBoundingClientRect() { return { width: 320, height: 240, left: 0, top: 0, right: 320, bottom: 240 }; }
    getContext() { if (!canvasContext) canvasContext = makeContext2D(); canvasContext.canvas = this; return canvasContext; }
    toDataURL() { return 'data:image/png;base64,AAAA'; }
    click() { if (typeof this.onclick === 'function') this.onclick({}); }
    setAttribute(name, value) { (this._attrs = this._attrs || {})[name] = value; }
    getAttribute(name) { return (this._attrs || {})[name]; }
    setAttributeNS(ns, name, value) { this.setAttribute(name, value); }
    focus() {}
}
// ---------- IndexedDB finto ----------
function makeRequest(exec) {
    const request = { result: undefined, error: null, onsuccess: null, onerror: null };
    queueMicrotask(() => {
        try {
            request.result = exec();
            if (request.onsuccess) request.onsuccess({ target: request });
        } catch (error) {
            request.error = error;
            if (request.onerror) request.onerror({ target: request });
        }
    });
    return request;
}

class FakeObjectStore {
    constructor(name) { this.name = name; this.rows = new Map(); this.autoKey = 1; }
    add(value) {
        return makeRequest(() => {
            const key = value.id !== undefined ? value.id : this.autoKey++;
            this.rows.set(key, value);
            return key;
        });
    }
    put(value) {
        return makeRequest(() => {
            const key = value.sessionId !== undefined ? value.sessionId : this.autoKey++;
            this.rows.set(key, value);
            return key;
        });
    }
    get(key) { return makeRequest(() => this.rows.get(key)); }
    delete(key) { return makeRequest(() => { this.rows.delete(key); }); }
    getAll() { return makeRequest(() => [...this.rows.values()]); }
    openCursor() {
        const request = { result: null, error: null, onsuccess: null, onerror: null };
        const items = [...this.rows.entries()];
        let index = 0;
        const step = () => {
            if (index >= items.length) { request.result = null; if (request.onsuccess) request.onsuccess({ target: request }); return; }
            const [key, value] = items[index++];
            request.result = {
                value,
                update: newValue => { this.rows.set(key, newValue); },
                delete: () => { this.rows.delete(key); },
                continue: () => queueMicrotask(step)
            };
            if (request.onsuccess) request.onsuccess({ target: request });
        };
        queueMicrotask(step);
        return request;
    }
}

class FakeTransaction {
    constructor(db) { this.db = db; }
    objectStore(name) { return this.db.stores.get(name); }
}

class FakeDB {
    constructor() { this.stores = new Map(); this.objectStoreNames = { contains: name => this.stores.has(name) }; }
    createObjectStore(name) { this.stores.set(name, new FakeObjectStore(name)); return this.stores.get(name); }
    transaction() { return new FakeTransaction(this); }
    close() {}
}
// ---------- Ambiente completo ----------
function createEnvironment({ html, onFetch } = {}) {
    const ids = new Set([...String(html || '').matchAll(/id="([^"]+)"/g)].map(m => m[1]));
    ids.forEach(id => ELEMENTS.set(id, new El('div', id)));

    const localStore = new Map();
    const db = new FakeDB();
    const calls = { fetch: [], confirm: [], prompt: [], toasts: [] };

    const queryCache = new Map();
    const documentStub = {
        getElementById: id => ELEMENTS.get(id) || null,
        createElement: tag => new El(tag),
        createElementNS: (ns, tag) => new El(tag),
        querySelectorAll: () => [],
        querySelector: selector => { if (!queryCache.has(selector)) queryCache.set(selector, new El('div')); return queryCache.get(selector); },
        body: new El('body'),
        addEventListener() {}, removeEventListener() {},
        visibilityState: 'visible'
    };

    const ctx = createContext({
        console, Blob, FormData, AbortController, URL, TextEncoder, TextDecoder,
        btoa, atob,
        queueMicrotask, setTimeout, clearTimeout, setInterval, clearInterval, structuredClone,
        document: documentStub,
        navigator: {
            userAgent: 'smoke-test',
            mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) },
            clipboard: { writeText: async () => {} },
            serviceWorker: { register: async () => ({}) },
            wakeLock: { request: async () => ({ release() {} }) }
        },
        indexedDB: {
            open: () => {
                const request = { result: db, error: null, onsuccess: null, onerror: null, onupgradeneeded: null };
                queueMicrotask(() => {
                    if (request.onupgradeneeded) request.onupgradeneeded({ target: { result: db } });
                    if (request.onsuccess) request.onsuccess({ target: request });
                });
                return request;
            }
        },
        localStorage: {
            getItem: key => (localStore.has(key) ? localStore.get(key) : null),
            setItem: (key, value) => { localStore.set(key, String(value)); },
            removeItem: key => { localStore.delete(key); },
            clear: () => localStore.clear()
        },
        location: { protocol: 'http:', href: 'http://localhost/index.html' },
        devicePixelRatio: 1,
        requestAnimationFrame: fn => setTimeout(fn, 0),
        cancelAnimationFrame: id => clearTimeout(id),
        addEventListener() {}, removeEventListener() {},
        confirm: message => { calls.confirm.push(message); return true; },
        prompt: (message, value) => { calls.prompt.push(message); return value; },
        alert: () => {},
        Notification: class { constructor() {} static requestPermission() { return Promise.resolve('granted'); } },
        MediaMetadata: class { constructor() {} },
        Image: class {
            constructor() { this.complete = false; this.naturalWidth = 0; }
            set src(value) {
                this._src = value;
                queueMicrotask(() => {
                    this.complete = true; this.naturalWidth = 1; this.naturalHeight = 1;
                    if (this.onload) this.onload();
                });
            }
            get src() { return this._src; }
        },
        XMLSerializer: class { serializeToString() { return '<svg></svg>'; } },
        Chart: class {
            constructor(context, config) { this.config = config; this.canvas = context?.canvas; }
            destroy() {}
            toBase64Image() { return 'data:image/png;base64,CHART'; }
        },
        MediaRecorder: class {
            static isTypeSupported() { return true; }
            constructor(stream, options = {}) {
                this.stream = stream;
                this.mimeType = options.mimeType || 'audio/webm';
                this.state = 'inactive';
                this._events = {};
            }
            addEventListener(type, fn) { (this._events[type] = this._events[type] || []).push(fn); }
            start() { this.state = 'recording'; }
            stop() {
                this.state = 'inactive';
                if (this.ondataavailable) this.ondataavailable({ data: new Blob([new Uint8Array([1, 2, 3, 4])], { type: this.mimeType }) });
                queueMicrotask(() => {
                    if (this.onstop) this.onstop();
                    (this._events.stop || []).forEach(fn => fn());
                });
            }
            pause() { this.state = 'paused'; }
            resume() { this.state = 'recording'; }
        },
        AudioContext: class {
            constructor() { this.state = 'running'; this.currentTime = 0; }
            createAnalyser() { return { fftSize: 256, frequencyBinCount: 128, getByteFrequencyData() {}, connect() {} }; }
            createMediaStreamSource() { return { connect() {} }; }
            createOscillator() { return { type: '', frequency: { setValueAtTime() {} }, connect() {}, start() {}, stop() {} }; }
            createGain() { return { gain: { setValueAtTime() {} }, connect() {} }; }
            get destination() { return {}; }
            resume() { this.state = 'running'; return Promise.resolve(); }
            close() { return Promise.resolve(); }
        },
        fetch: async (url, options = {}) => {
            calls.fetch.push({ url: String(url), options });
            const body = onFetch ? onFetch(String(url), options) : {};
            return { ok: true, status: 200, type: 'basic', json: async () => body, text: async () => JSON.stringify(body) };
        }
    });
    ctx.window = ctx;        // dentro l'app: window.X = ... deve funzionare
    ctx.self = ctx;
    ctx.__calls = calls;
    ctx.__store = localStore;
    ctx.__elements = ELEMENTS;
    ctx.__db = db;
    return ctx;
}

module.exports = { createEnvironment, El, ELEMENTS, CREATED };


