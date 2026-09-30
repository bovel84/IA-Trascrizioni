/* Android keeps recording/transcribing in the service; this file only updates the original UI. */
window.initNativeRecorder = function () {
    if (!window.AndroidRecorder || window.nativeRecorderReady) return;
    window.nativeRecorderReady = true;
    const bridge = window.AndroidRecorder;
    let startRequested = false, wasActive = false, wasRetrying = false, lastArchive = '';
    let seen;
    try { seen = new Set(JSON.parse(localStorage.getItem('voicescribe_native_seen') || '[]')); }
    catch (e) { seen = new Set(); }
    transcriptEntries.forEach(entry => { if (entry.nativeSegmentId) seen.add(entry.nativeSegmentId); });
    window.nativeAudioSession = window.nativeAudioSession || null;

    function entryFromNative(entry) {
        return { ...entry, speaker: 1, speakerName: speakers[0], time: formatDuration(entry.offsetMs), timestamp: Date.now() };
    }
    function saveArchives(states) {
        let changed = false;
        const hidden = JSON.parse(localStorage.getItem('voicescribe_native_hidden') || '[]');
        for (const state of states) {
            if (!state.sessionId || hidden.includes(state.sessionId)) continue;
            let record = savedRecordings.find(r => r.nativeAuto && r.nativeAudioSession === state.sessionId);
            if (!record) {
                const id = Number(state.sessionId.replace('session-', ''));
                record = { id, name: `Registrazione ${new Date(id).toLocaleString('it-IT')}`, date: new Date(id).toISOString(),
                    nativeAuto: true, nativeAudioSession: state.sessionId, speakers: [...speakers], notes: '',
                    hasAudio: true, hasNotes: false, hasDrawing: false, hasChart: false, hasMap: false };
                savedRecordings.push(record);
            }
            record.entries = (state.entries || []).map(entryFromNative);
            record.duration = formatDuration(state.elapsedMs || 0);
            changed = true;
        }
        if (changed) {
            try { localStorage.setItem('voicescribe_recordings', JSON.stringify(savedRecordings)); }
            catch (e) { showToast('Spazio progetti esaurito; audio nativo conservato'); }
            renderSavedRecordings();
        }
    }
    function importEntries(state) {
        if (window.nativeAudioSession !== state.sessionId) return;
        let changed = false;
        for (const entry of state.entries || []) {
            if (seen.has(entry.nativeSegmentId)) continue;
            const before = transcriptEntries.length;
            addEntry(entry.text, entry.offsetMs);
            if (transcriptEntries.length > before) transcriptEntries[transcriptEntries.length - 1].nativeSegmentId = entry.nativeSegmentId;
            seen.add(entry.nativeSegmentId); changed = true;
        }
        if (changed) {
            try { localStorage.setItem('voicescribe_native_seen', JSON.stringify([...seen])); } catch (e) { }
            scheduleAutosave(true);
        }
    }
    window.nativeRecorderError = message => {
        startRequested = false; isProcessing = false; updateUI(); showToast(message);
    };
    window.applyNativeRecorderState = state => {
        if (state.active) {
            startRequested = false;
            if (state.sessionId && window.nativeAudioSession !== state.sessionId) {
                window.nativeAudioSession = state.sessionId; audioBlob = null;
            }
        } else if (/^Avvio fallito/.test(state.status || '')) startRequested = false;
        isRecording = !!state.recording;
        isPaused = !!state.paused;
        isProcessing = startRequested || !!state.starting || !!state.retrying || (!!state.active && !state.recording);
        if (window.nativeAudioSession === state.sessionId) {
            importEntries(state);
            $('timer').textContent = formatDuration(state.elapsedMs || 0);
            chunkCount = state.chunkCount || 0;
        }
        updateUI(); updateSpeakerChips();
        $('bgBadge').style.display = state.recording ? 'flex' : 'none';
        $('bgWarning').classList.toggle('active', !!state.recording);
        $('chunkIndicator').textContent = `Chunk: ${state.chunkCount || 0}${state.pending ? ` · in coda: ${state.pending}` : ''}`;
        $('chunkIndicator').classList.toggle('active', !!state.active || !!state.pending);
        $('recordHint').textContent = state.active || state.retrying ? (state.status || 'Registrazione in corso') : 'Tocca per registrare';
        if (!state.active && state.status && state.status !== 'Pronto') $('recordHint').textContent = state.status;
        const bars = $('visualizer').querySelectorAll('.visualizer-bar');
        bars.forEach((bar, i) => { bar.style.height = `${Math.max(8, (state.level || 0) * (32 + (i % 4) * 4))}px`; });
        const stamp = `${state.sessionId}:${(state.entries || []).length}:${state.chunkCount}:${!!state.active}`;
        if (state.sessionId && stamp !== lastArchive) { saveArchives([state]); lastArchive = stamp; }
        if (wasActive && !state.active) { audioBlob = null; scheduleAutosave(true); }
        if (wasRetrying && !state.retrying) {
            const states = JSON.parse(bridge.sessions()); saveArchives(states);
            states.forEach(importEntries);
        }
        wasActive = !!state.active; wasRetrying = !!state.retrying;
    };
    window.syncNativeRecorder = () => {
        try { window.applyNativeRecorderState(JSON.parse(bridge.state())); }
        catch (e) { showToast('Impossibile leggere lo stato Android'); }
    };
    startRecording = async function () {
        if (!whisperKey) return showToast('⚠️ Inserisci chiave Whisper');
        if (isRecording || isProcessing) return;
        audioBlob = null; startRequested = true; isProcessing = true; updateUI();
        bridge.start(whisperKey, sttModel, $('languageSelect').value);
    };
    stopRecording = async function () { bridge.stop(); };
    window.togglePause = () => { if (isRecording) { bridge.pause(!isPaused); isPaused = !isPaused; updateUI(); } };
    const webExportAudio = window.exportAudio;
    window.exportAudio = async () => {
        if (window.nativeAudioSession) return bridge.exportAudio(window.nativeAudioSession);
        return webExportAudio();
    };
    const webLoad = window.loadRec;
    window.loadRec = async id => {
        if (isRecording || isProcessing) return showToast('Ferma prima la registrazione');
        await webLoad(id);
        // Stored edits/deletions are authoritative; don't reinsert deleted raw segments.
        const state = JSON.parse(bridge.sessions()).find(s => s.sessionId === window.nativeAudioSession);
        if (state) (state.entries || []).forEach(entry => seen.add(entry.nativeSegmentId));
    };
    const webDelete = window.delRec;
    window.delRec = async id => {
        const record = savedRecordings.find(r => r.id === id);
        await webDelete(id);
        if (record?.nativeAuto && !savedRecordings.some(r => r.id === id)) {
            const hidden = JSON.parse(localStorage.getItem('voicescribe_native_hidden') || '[]');
            hidden.push(record.nativeAudioSession); localStorage.setItem('voicescribe_native_hidden', JSON.stringify(hidden));
        }
    };
    $('backgroundToggle').checked = true; $('backgroundToggle').disabled = true;
    const setting = document.querySelector('.setting-info');
    if (setting) setting.innerHTML = '<h4>🔒 Schermo spento</h4><p>Registrazione Android con notifica persistente</p>';
    $('bgWarning').textContent = 'REGISTRAZIONE ANDROID · PUOI BLOCCARE LO SCHERMO';
    const section = document.querySelector('.retranscribe-section');
    if (section) {
        const retry = document.createElement('button'); retry.className = 'retranscribe-btn'; retry.textContent = '🔄 Riprova audio non trascritto';
        retry.onclick = () => {
            if (!window.nativeAudioSession) return showToast('Apri prima una registrazione salvata');
            if (!whisperKey) return showToast('Inserisci chiave Whisper');
            if (isRecording || isProcessing) return showToast('Attendi il completamento della registrazione');
            bridge.retry(window.nativeAudioSession, whisperKey, sttModel, $('languageSelect').value);
        };
        section.appendChild(retry);
        const battery = document.createElement('button'); battery.className = 'model-refresh'; battery.textContent = '🔋 Impostazioni batteria'; battery.onclick = () => bridge.batterySettings(); section.appendChild(battery);
    }
    // Blob/data downloads from the existing PDF/TXT/SRT/VTT/PNG buttons use Android's save dialog.
    if (window.HTMLAnchorElement) {
        const originalClick = HTMLAnchorElement.prototype.click;
        const originalDispatch = HTMLAnchorElement.prototype.dispatchEvent;
        function downloadAnchor(anchor) {
            const name = anchor.download, source = anchor.href;
            fetch(source).then(response => response.blob()).then(blob => new Promise((resolve,reject) => {
                const reader = new FileReader(); reader.onload = () => resolve({ data: String(reader.result).split(',')[1], type: blob.type }); reader.onerror = reject; reader.readAsDataURL(blob);
            })).then(file => bridge.download(name, file.type, file.data)).catch(() => showToast('Export non riuscito'));
        }
        HTMLAnchorElement.prototype.click = function () {
            if (!this.download || !/^(blob:|data:)/.test(this.href)) return originalClick.call(this);
            downloadAnchor(this);
        };
        // jsPDF/FileSaver dispatches a synthetic click on a detached anchor.
        HTMLAnchorElement.prototype.dispatchEvent = function (event) {
            if (event.type === 'click' && this.download && /^(blob:|data:)/.test(this.href)) { downloadAnchor(this); return true; }
            return originalDispatch.call(this, event);
        };
    }
    saveArchives(JSON.parse(bridge.sessions()));
    window.syncNativeRecorder();
    setInterval(window.syncNativeRecorder, 1000);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') window.syncNativeRecorder(); });
};
