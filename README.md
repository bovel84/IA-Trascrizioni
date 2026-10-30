# VoiceScribe Pro

## Esportazione Android affidabile — 2.7.2

Il salvataggio dei file usa una coda indipendente dalla trascrizione e conserva su disco sorgente e stato prima di aprire il selettore documenti. La ricreazione della schermata o del processo non perde il riferimento all'audio. I WAV preparati per l'esportazione restano nei file privati dell'app fino al completamento; un errore conserva il file per **Riprova salvataggio**. **Annulla salvataggio** elimina soltanto l'eventuale copia temporanea, lasciando i segmenti registrati e gli originali importati.

La conferma arriva dopo copia e chiusura del flusso, con numero di byte effettivamente scritti. Una destinazione vuota/incompleta, quando il provider ne espone la dimensione, viene segnalata come errore. Una sessione senza campioni audio non apre il selettore né produce un file vuoto. Un WAV silenzioso rimane invece un WAV non vuoto. Verifiche aggiunte: esportazione WAV di due ore, conservazione dei sorgenti, errori di scrittura/chiusura, ripresa dopo ricreazione e copia indipendente da una trascrizione bloccata.

## Correzione registrazioni lunghe — 2.7.1

- Risultato e avanzamento restano visibili: segmenti trascritti/totali, segmenti in attesa, errore del provider o nessun parlato riconosciuto. Un recupero parziale non viene indicato come completato.
- In caso di rete, chiave o quota non disponibili la cattura continua a salvare l'audio; dopo i tentativi limitati sul primo segmento fallito la trascrizione si sospende. Non si accumulano ore di richieste fallite. I brevi `Retry-After` vengono rispettati; per attese lunghe resta disponibile il recupero manuale.
- **💾 → apri la registrazione → Riprova audio non trascritto** recupera i segmenti mancanti nell'APK. I WAV nativi e le trascrizioni già ottenute rimangono su disco anche dopo la chiusura dell'app. La versione web ha lo stesso pulsante e conserva risultati e coda in IndexedDB, senza trattenere tutti i segmenti nella memoria JavaScript.
- Il timeout web comprende anche la lettura della risposta. Risposte prive di un campo testo valido e fallimenti di salvataggio restano errori recuperabili; una risposta con testo vuoto viene indicata come assenza di parlato.
- Verifica aggiunta: `node tools/long-recording-test.js`, test del collegamento Android con 240 segmenti e test JVM su due ore di WAV, ripresa a metà, errori HTTP e timeout. Sono prove simulate senza chiamate API: non attestano la qualità su audio reale né il comportamento del dispositivo a schermo bloccato per due ore.

Per una registrazione precedente senza testo: conservarla ed esportarne l'audio prima di reinstallare/disinstallare l'app. Il recupero è possibile se i segmenti audio sono ancora presenti sul dispositivo; l'aggiornamento non può ricreare audio già eliminato.

Web app in **un unico file HTML** per registrare audio, trascriverlo (Groq Whisper), prendere appunti, disegnare, analizzare il contenuto con un LLM (Groq) e riportare tutto in PDF/TXT/SRT/VTT/Markdown.

## File del progetto

| File | Descrizione |
| --- | --- |
| `index.html` | L'applicazione completa (HTML + CSS + JS). È l'unico file necessario. |
| `manifest.webmanifest` | Manifest PWA (nome, icone, colori, modalità standalone). |
| `sw.js` | Service worker: rende l'app installabile e funzionante **offline**. |
| `icon.svg` | Icona dell'app (usata da manifest e apple-touch-icon). |
| `tools/check-app.js` | Controlli statici: sintassi degli script inline, coerenza degli `id`, presenza delle feature. |
| `tools/smoke-test.js` | Test end-to-end: esegue l'app in un DOM simulato e verifica i flussi reali. |
| `tools/dom-stub.js` | DOM/IndexedDB/API browser simulate usate dai test. |
| `tools/inspect-lines.js` | Debug: stampa righe del sorgente con caratteri non ambigui. |
| `index53.backup-*.html` | Copia di sicurezza dell'originale (solo locale, esclusa da Git). |

## Come si avvia

- **Semplice**: apri `index.html` nel browser (o copialo sul telefono e aprilo) — tutto funziona tranne la parte PWA.
- **Come app installabile / offline**: servi la cartella in HTTP(S), ad esempio
  ```powershell
  npx serve .      # oppure: python -m http.server 8080
  ```
  poi apri `http://localhost:8080/index.html` e usa "Aggiungi a schermata Home".
  Il service worker **non** si registra da `file://` (limite dei browser, non dell'app).

## Chiavi API e modelli

1. Prendi una chiave su <https://console.groq.com/keys> e incollala nei due campi in alto (vanno bene anche la stessa chiave).
2. Scegli i **modelli** con i due menu a tendina:
   - **Whisper**: `whisper-large-v3-turbo` (veloce, costo ~1/3) oppure `whisper-large-v3` (massima precisione).
   - **LLM**: `openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `llama-3.3-70b-versatile`.
   - Il pulsante **"Verifica i modelli disponibili"** interroga l'endpoint `/models` con la tua chiave e mostra solo i modelli realmente utilizzabili (Groq depreca i modelli nel tempo).

> Nota storica: il modello `llama-3.1-8b-instant` usato dalla versione precedente è diventato "Enterprise" e non è più utilizzabile con i piani standard: per questo è stato sostituito.

## Cosa fa ora l'app

**Registrazione e trascrizione**
- L'audio viene registrato a chunk di 2 minuti e **ogni chunk viene trascritto appena chiuso**: la trascrizione compare in tempo reale (in coda: `Chunk: n · in coda: m`), senza aspettare la fine della sessione.
- Timestamp reali per ogni frase (offset del chunk rispetto all'inizio, pause escluse).
- Retry automatico con backoff su errori di rete, `429` e `5xx`; timeout a 90" per chiamata.
- L'audio resta **solo su IndexedDB** (nessuna doppia copia in RAM): le sessioni lunghe non esauriscono più la memoria del browser.
- Sessioni interrotte: all'avvio viene proposto il recupero dei chunk rimasti; a fine sessione i chunk passano a `current_audio` e non vengono più riproposti.
- Pausa/riprendi, opzione mantieni schermo attivo (solo pagina visibile), visualizzatore, contatori.

**Trascrizione (workflow)**
- Ricerca nella trascrizione con evidenziazione dei risultati.
- Su ogni frase: **✏️ modifica**, **📋 copia**, **🗑️ elimina**; un tocco sul nome dello speaker passa a quello successivo.
- Appunti + disegno su canvas (penna, gomma, linea, rettangolo, cerchio, colori, dimensioni) con undo.

**Analisi AI**
- Correttore, riassunto, punti chiave, riscrittura, grafico (Chart.js) e mappa concettuale (SVG).
- JSON mode + parser tollerante (gestisce blocchi ```json e testo di contorno), fallback automatico sui parametri non supportati dal modello.
- L'output del modello viene inserito con `textContent` (nessun HTML interpretato).

**Salvataggio ed export**
- Autosalvataggio della bozza ogni 30" e alla chiusura; all'avvio viene proposta la bozza non salvata.
- Avviso del browser se si chiude la pagina durante una registrazione/elaborazione.
- Progetti salvati su localStorage (testo/note/grafico) + IndexedDB (audio e disegni), con gestione della memoria piena: se localStorage è saturo viene proposto di salvare senza le immagini di grafico/mappa.
- Export: PDF, TXT, **SRT**, **VTT**, **Markdown**, audio (con estensione corretta) e disegno PNG.

## Sicurezza dei dati

- Le chiavi API sono salvate in `localStorage` del browser (in chiaro) e le richieste partono direttamente dal browser verso `api.groq.com`: usale su un dispositivo di cui ti fidi.
- I progetti stanno solo sul tuo dispositivo (nessun server intermedio).

## Sviluppo e verifica

```powershell
node tools/check-app.js     # controlli statici (sintassi JS, id, feature presenti)
node tools/smoke-test.js    # test end-to-end (59 controlli) in DOM simulato
node --check sw.js          # sintassi del service worker
```

## Repository GitHub e allineamento

Il progetto è allineato al repository pubblico **<https://github.com/bovel84/IA-Trascrizioni>** (branch `main`):

- `origin/main` contiene la storia del progetto e il ramo locale `main` è impostato per tracciarlo.
- L'app attuale è pubblicata come `index.html`: con **GitHub Pages** è raggiungibile su <https://bovel84.github.io/IA-Trascrizioni/>.
- La versione precedente dell'app resta consultabile nella storia: `git show 4df30dc:index.html`.

```powershell
git pull --rebase          # porta sul PC le modifiche fatte su GitHub
git push                   # pubblica le modifiche locali
git log --oneline --graph  # cronologia completa
git diff                   # modifiche non ancora salvate
git restore .              # annulla le modifiche non committate
git branch -a              # rami locali (storico-locale) e remoti
```

Il ramo locale `storico-locale` conserva i commit creati sul PC prima dell'allineamento (validazione + fasi 1-2-3).

## Funzioni della versione precedente (nel repo) non ancora portate

Trovate confrontando `git show 4df30dc:index.html` con l'app attuale:

- **Trascrizione con la Web Speech API del browser**: modalità alternativa senza chiave API (piano B se Groq non è disponibile o si è offline).
- **Avviso/diagnostica HTTPS per mobile**: sull'hosting statico il microfono richiede HTTPS, un avviso esplicito aiuta a capire subito perché la registrazione non parte.
- **Controllo dimensione file (max 25 MB)** prima dell'invio a Whisper.

Se ti interessano, si possono riportare nella versione attuale.

## Limiti noti

- La trascrizione in tempo reale dipende dalla rete: senza connessione i chunk restano in coda e vengono ritentati alla fine.
- Groq Whisper non separa automaticamente le voci. La modalità Deepgram opzionale descritta sotto analizza l’audio dopo la registrazione; non è riconoscimento dei nomi o delle identità fra riunioni diverse.
- Un nuovo avvio di registrazione azzera l'audio precedente della sessione corrente (la trascrizione resta): salva il progetto prima di ricominciare.

## App Android con grafica originale e schermo spento

L’APK **VoiceScribe Pro 1.1** include lo stesso `index.html` della PWA: tema, schede, appunti, disegno, AI, progetti ed export. `native-bridge.js` collega l’interfaccia al foreground service Android, così registrazione e trascrizione continuano indipendentemente dalla pagina. I progetti conservano il riferimento ai WAV nativi e il testo modificato.

VoiceScribe Pro (`it.voicescribe.pro`) si installa accanto alla precedente VoiceScribe Audio, mantenendo disponibili i dati della vecchia app. I due archivi non vengono migrati automaticamente.

Il workflow GitHub **Android APK** genera l’APK installabile ed esegue 59 controlli web, 22 controlli del collegamento Android e 3 test WAV. La registrazione a schermo bloccato va comunque provata sul dispositivo dopo l’aggiornamento.

Vedere [android/README.md](android/README.md) per build, installazione, file cambiati e test. La PWA aperta nel browser conserva i limiti del browser sul microfono in background: il servizio è disponibile solo nell’APK.


## VoiceScribe 1.9 — partecipanti e riconoscimento delle voci

- **Senza un nuovo servizio:** seleziona il partecipante mentre parla. Web e Android chiudono il segmento al cambio; il nome resta associato all’audio anche se la risposta arriva più tardi o il telefono è bloccato. Le registrazioni Android precedenti senza etichette risultano «Non assegnato».
- **Automatico, facoltativo:** apri **Partecipanti → Riconoscimento automatico delle voci**, inserisci una chiave **Deepgram**, poi premi **Distingui le voci nell’audio** a registrazione conclusa. Puoi anche scegliere direttamente un file audio, senza passare da Groq.
- L’intero audio viene inviato una sola volta a Deepgram Nova-3 con diarizzazione v2; non ci sono chiamate al modello chat né retry automatici. Il servizio richiede connessione e credito API. La chiave rimane solo nel campo della pagina, senza localStorage o progetti.
- Prima dell’invio viene chiesta conferma. Dopo l’analisi puoi applicare o scartare il risultato: applicarlo sostituisce il testo corrente e le correzioni manuali. I progetti già salvati restano intatti; un errore, una risposta incompleta o una modifica contemporanea lasciano invariato il testo.
- I risultati hanno etichette **Voce 1, Voce 2…**. Usa **✎** per rinominare tutte le frasi di una voce; tocca l’etichetta di una frase per assegnarla a un altro partecipante. Sono supportati fino a 32 partecipanti. I nomi reali non vengono dedotti automaticamente.
- Le assegnazioni senza confidenza o con confidenza media sotto 0,65 sono marcate **da verificare**: la soglia è un criterio prudenziale dell’app, non una garanzia di accuratezza. Rumore e persone che parlano insieme possono produrre errori.
- **Salva Progetto** conserva etichette e correzioni. SRT/VTT usano anche i tempi finali restituiti dal servizio. La bozza conserva lo stato di diarizzazione, evitando che il refresh Android reinserisca il testo precedente.
- Android unisce i WAV su disco e li invia in streaming, fino al limite di 2 GB del servizio. Nel browser i segmenti WebM separati vengono convertiti in un WAV valido prima di salvarli, esportarli o analizzarli (massimo 128 MiB PCM per contenere la memoria). I vecchi file WebM già esportati per concatenazione non vengono riparati da questa conversione.
- La diarizzazione viene eseguita dopo la registrazione: mantieni l’app aperta fino al risultato. La registrazione nativa continua invece a supportare lo schermo bloccato.

Verifica locale: `node tools/check-app.js`, `node tools/smoke-test.js`, `node tools/native-bridge-test.js`, `node tools/provider-test.js`, `node tools/speakers-test.js`. Le risposte API sono simulate: qualità su voci reali, rete e accesso al servizio devono essere verificati sul telefono con un audio di prova. Nessuna chiamata API a pagamento viene eseguita dai test.

Riferimenti API: [diarizzazione Deepgram](https://developers.deepgram.com/docs/diarization), [utterances](https://developers.deepgram.com/docs/utterances), [limiti file](https://developers.deepgram.com/docs/pre-recorded-audio).


## VoiceScribe 2.0 — nuovo spazio di lavoro

Interfaccia adattiva: navigazione inferiore su telefono, menu laterale e registratore affiancato alla trascrizione su desktop. Connessioni e modelli sono raccolti in un pannello espandibile, raggiungibile anche da Impostazioni. Pulsanti più grandi, contrasto migliorato, ricerca nel testo più ampia e nomi/azioni dei partecipanti raggruppati. Appunti, disegno, analisi AI, grafici, mappa, progetti e tutti gli otto export/salvataggi mantengono gli stessi handler. Zoom della pagina consentito, focus da tastiera visibile, etichette dei comandi di registrazione aggiornate e animazioni ridotte se richiesto dal dispositivo. APK 2.0 / versionCode 11, cache PWA v11.
