# VoiceScribe Pro

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
- La diarizzazione (chi parla) è **manuale**: si cambia speaker con un tocco durante la registrazione.
- La trascrizione non separa automaticamente le persone in una conversazione.
- Un nuovo avvio di registrazione azzera l'audio precedente della sessione corrente (la trascrizione resta): salva il progetto prima di ricominciare.

## Registrazione sul cellulare a schermo spento

La PWA non può garantire cattura del microfono o trascrizione continua a schermo bloccato. L’opzione web mantiene lo schermo attivo finché la pagina è visibile: non abilita un servizio Android. Sono stati rimossi i tentativi di mantenimento tramite audio artificiale.

Per Android è stata aggiunta l’app nativa **VoiceScribe Audio** in `android/`: foreground service microphone con notifica e Ferma, WAV persistenti e trascrizione Groq indipendenti dalla schermata. Ha una schermata dedicata, archivi separati dalla PWA ed export ZIP; analisi e appunti restano nella PWA. Non è un aggiornamento automatico dell’app installata dal browser.

Vedere [android/README.md](android/README.md) per compilazione, file modificati, limitazioni e prove sul telefono. La verifica web passa 59 controlli nel DOM simulato; i sorgenti Java compilano contro Android 35 e passano 3 test WAV. La build APK e la prova reale Android a schermo bloccato restano necessarie.
