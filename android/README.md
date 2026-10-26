# VoiceScribe Pro Android — microfono e audio dei video

## Strumenti e risultati del progetto (2.2)

La scheda **Analisi AI** permette di scegliere trascrizione, appunti o entrambi.
**Correggi** preserva il significato, **Migliora** rielabora il registro, **Riassumi**
separa sintesi/decisioni/questioni/prossimi passi, **Punti chiave** distingue azioni,
responsabili, scadenze e proposte. Le istruzioni vietano di inventare dati mancanti.
I testi lunghi vengono suddivisi e consolidati; gli output troncati sono segnalati,
senza salvare un risultato incompleto. I limiti di contesto effettivi dipendono dal modello.

- **Grafico**: il conteggio delle parole per partecipante è calcolato sul testo e non
  richiede una chiamata AI. In modalità **Dati numerici citati** ogni valore deve avere
  una citazione presente nel contenuto, con etichetta e numero verificabili; la citazione
  compare nel risultato e nel CSV. Non sono stime della durata del parlato. Le evidenze
  verificano la corrispondenza testuale, non sostituiscono una revisione umana del contesto.
- **Mappa**: ID, nodi e collegamenti validati, etichette leggibili, impaginazione adattata
  alla larghezza disponibile. Il testo delle relazioni rimane consultabile nel risultato.
- Ogni strumento conserva il suo ultimo risultato, selezionabile, copiabile, scaricabile
  e aggiungibile agli appunti. Risultati e diagrammi vengono salvati nel progetto/bozza;
  PDF, TXT e Markdown includono i risultati testuali. Grafici: PNG/CSV. Mappe: SVG/PNG.
- **Annulla** interrompe l'attesa e chiede al bridge Android di chiudere la connessione.
  Le risposte tardive non possono modificare un altro progetto o un contenuto cambiato.
  Un annullamento non revoca l'elaborazione già ricevuta dal provider.
- Errori di rete o di formato conservano i risultati precedenti. Non vengono effettuati
  reinvii automatici per errori di rete della generazione AI. Rimangono solo i fallback
  per parametri esplicitamente rifiutati dal provider.
- Import audio: file fino a 25 MB; una trascrizione fallita conserva la sessione precedente.
  Un'importazione riuscita diventa una nuova sessione e viene conservata in IndexedDB.
- Le bozze con soli appunti/disegni sono recuperabili; disegni con annulla/ripristina e
  coordinate adattate al ridimensionamento. La copia dei risultati usa la clipboard nativa
  nell'APK. Gli errori di salvataggio audio/disegno sono mostrati prima di dichiarare il successo.

Verifiche aggiunte: test di analisi con risposte simulate, flussi browser dei sei strumenti,
11 download reali, riapertura del progetto e layout 320/390/768/1440 px. Non sono una prova
con le chiavi reali dell'utente né della registrazione sul suo dispositivo.

## Audio riprodotto sullo stesso telefono o tablet (2.1)

Nella scheda **Rec**, scegliere **Sorgente audio → Audio del dispositivo (video)**.
Premere **Registra**, consentire i permessi richiesti e confermare la condivisione Android.
Passare all'app del video e avviarne la riproduzione. Tornare a VoiceScribe per leggere la
trascrizione, mettere in pausa o fermare; **Ferma** funziona anche dalla notifica.
Salvataggio automatico, progetti, recupero dei segmenti e download WAV funzionano come
con il microfono. Per tornare alle riunioni, fermare e scegliere **Microfono**.

- Richiede l'APK su **Android 10+**; la scelta è disabilitata su Android 8/9 e con APK
  precedenti. La PWA nel browser continua a registrare il microfono.
- Acquisisce **solo l'audio interno**, senza aprire il microfono o registrare immagini.
  Non richiede il vivavoce; l'audio passa dall'API di cattura Android anche usando cuffie.
- Usa `AudioPlaybackCaptureConfiguration`/`MediaProjection` in un servizio di tipo
  `mediaProjection`. Il consenso di sistema è richiesto **a ogni nuova registrazione**.
  Su Android 14+ il servizio viene promosso prima di ottenere il token di proiezione;
  il servizio microfono continua a dichiarare esclusivamente il tipo `microphone` a runtime.
- Sono acquisibili i flussi media/game/unknown delle app che lo consentono, nello stesso
  profilo Android. App che vietano la cattura, contenuti protetti, telefonate e flussi
  di comunicazione non sono supportati. Nessun aggiramento o ripiego automatico sul microfono.
- Dopo circa 8 secondi di silenzio digitale compare **Nessun audio ricevuto**: avviare
  il video o provare un'app che consenta la cattura. I WAV mantengono silenzio e tempi,
  ma i segmenti interamente silenziosi non vengono inviati a Whisper, anche al recupero.
- Se Android revoca la condivisione (incluso il blocco schermo sui sistemi che la
  interrompono), la cattura termina e l'audio acquisito viene finalizzato e conservato.
  Lo schermo spento resta supportato dal registratore **Microfono**; per i video mantenere
  la riproduzione attiva secondo le regole dell'app sorgente e del sistema.

Prova aggiuntiva su dispositivo: riprodurre un video consentito sul telefono e sul tablet,
anche con cuffie; verificare WAV e testo, cambio app, Pausa/Riprendi, Ferma dalla notifica,
annullamento del consenso, revoca della condivisione e una seconda registrazione con
nuovo consenso. Provare un'app che blocca la cattura: deve mostrare l'avviso, senza testo
inventato o audio del microfono. I test UI simulati e la build non sostituiscono questa prova.

Riferimento: [cattura audio di altre app](https://developer.android.com/media/platform/av-capture).

L'APK 1.7 usa **lo stesso `index.html` della PWA**: tema scuro, colori, microfono, schede Rec/Note/AI, disegno, progetti ed esportazioni. Il backup `index53.backup-20260930-090012.html` è stato consultato come riferimento grafico; non sono stati ripristinati i suoi vecchi bug/API. L'interfaccia HTML viene inclusa automaticamente nell'APK durante la build, senza copie manuali da mantenere.

La registrazione non è eseguita dalla pagina: `native-bridge.js` collega i pulsanti al servizio Android già presente. Il servizio continua a catturare WAV e trascrivere su Groq con schermo bloccato. La pagina sincronizza timer, stato, livello audio e nuovi risultati quando è visibile. Tornare dal background non reinserisce frasi già importate o annulla modifiche al testo.

## Installazione e dati precedenti

- Nome della nuova app: **VoiceScribe Pro**, package `it.voicescribe.pro`, Android 8+.
- La vecchia **VoiceScribe Audio** usa `it.voicescribe.recorder`: rimane installata con le sue registrazioni. Non è necessario disinstallarla. I dati non vengono migrati automaticamente tra i due package.
- Per trasferire l'audio precedente, esportare il ZIP dalla vecchia app, estrarre i WAV e usare **Carica Audio da Trascrivere** nella nuova. Le vecchie sessioni restano accessibili nella vecchia app.
- Inserire e salvare le chiavi Whisper/LLM nei campi originali. Come nella PWA, vengono conservate in `localStorage` privato dell'app, in chiaro. Non vengono incluse nell'APK o nel repository. L'audio viene inviato a Groq quando si trascrive.

## Uso

1. Avviare **Rec** dall'app visibile e consentire il microfono. Le notifiche vengono richieste su Android 13+; se negate, il servizio resta visibile in App attive e si può fermare dall'app.
2. Bloccare il telefono o premere Home: il microfono rimane nel foreground service, con notifica e pulsante **Ferma**.
3. Riaprendo l'app, la stessa schermata mostra il testo e il timer aggiornati. Pausa/Riprendi comandano il recorder Android.
4. Le registrazioni native compaiono automaticamente nella scheda **💾**. **Salva Progetto** conserva testo modificato, speaker, note, disegno e riferimento all'audio nativo; nessuna copia integrale del WAV viene caricata in RAM per salvare il progetto.
5. **Scarica Audio** produce un unico WAV, con intestazione corretta. PDF/TXT/SRT/VTT/Markdown/PNG aprono il selettore Android per scegliere dove salvare. Le librerie PDF e grafici sono incluse nell'APK; i font Google usano la rete e hanno un fallback di sistema offline.
6. **Riprova audio non trascritto** nella scheda 💾 recupera i segmenti pendenti della sessione aperta, compresi i `.part` dopo un'interruzione. **Impostazioni batteria** apre le normali impostazioni Android, senza richiedere esenzioni automatiche.

Eliminare un progetto non cancella i WAV nativi condivisi con altri progetti. Cancellare i dati dell'app/disinstallarla elimina invece tutto l'archivio: esportare ciò che serve prima.

## Registrazione a schermo spento

- Servizio non esportato, tipo **microphone**, permessi `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`; avvio solo da azione nella schermata visibile.
- PCM mono 16 bit/16 kHz con `AudioRecord`, segmenti WAV di circa 30 s, cattura indipendente da WebView e timer JavaScript. Circa 115 MB/ora di audio.
- `PARTIAL_WAKE_LOCK` durante cattura e completamento della coda: mantiene il thread PCM attivo, senza tenere acceso lo schermo. Rilasciato su completamento/errori/distruzione del servizio. Nessun audio artificiale.
- Trascrizione su thread separato, timeout di rete e retry per errori transitori. Senza rete/nel risparmio energetico l'audio resta conservato, ma l'arrivo del testo remoto può essere ritardato.
- Pausa chiude il segmento e scarta campioni; il microfono rimane aperto. Timestamp relativi all'audio, pause escluse.
- Notifica Ferma interrompe il microfono e completa la coda. Revoca microfono, errore AudioRecord o silenziamento segnalato dal sistema interrompono la cattura conservando quanto disponibile. `START_NOT_STICKY`: nessuna ripartenza automatica al boot/forza arresto.
- Forza arresto, arresto da App attive e politiche dei produttori possono terminare l'app. Non vengono aggirate. Il comportamento con chiamate e risparmio energetico va verificato sul telefono.

## WebView

Solo gli asset inclusi nell'APK vengono caricati come documento nel WebView, sull'origine HTTPS riservata `appassets.androidplatform.net`. Accesso a file locali disabilitato; CSP blocca iframe/oggetti/script remoti; jsPDF e Chart.js sono copie delle versioni già usate dalla PWA. I collegamenti HTTPS esterni vengono aperti nel browser. Il WebView non riceve il permesso microfono: lo gestisce esclusivamente il servizio nativo. Import/export usano il selettore documenti Android.

## Build e verifiche

Aprire `android/` in Android Studio con JDK 17 e SDK 35, oppure:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Il workflow **Android APK** su GitHub Actions esegue build e test e pubblica l'APK debug installabile in **VoiceScribe-Audio-APK**. È avviabile manualmente da Actions e si attiva con modifiche al codice Android, HTML o adapter. La firma debug è temporanea per ogni build: APK di esecuzioni diverse possono avere certificati differenti e richiedere reinstallazione (esportare i dati prima). Nessuna chiave di firma viene conservata nella cache o nel repository. Per aggiornamenti stabili occorre una chiave release privata configurata con autorizzazione esplicita.

Controlli: sintassi/ID, **59 test web**, **22 test adapter Android simulato**, **3 test JVM WAV**; sorgenti compilati contro API 35. Anteprima grafica verificata a 390×844, senza scorrimento orizzontale. Questi controlli non sono una prova fisica del nuovo APK sul telefono.

### Prova sul telefono

1. Verificare tema, tutte le schede, note/disegno, progetto salvato/riaperto e PDF/WAV esportati.
2. Registrare oltre 4 minuti; poi registrare 10 minuti con schermo bloccato pronunciando frasi riconoscibili ogni minuto. Riaprire e ascoltare il WAV: devono esserci le frasi prima, durante e dopo il blocco.
3. Provare Home/ritorno, rotazione, Pausa/Riprendi e Ferma dalla notifica. La trascrizione non deve duplicarsi, il servizio non deve dipendere dalla pagina. Il primo risultato arriva dopo il primo segmento (circa 30 secondi) e la risposta del provider; premendo Ferma viene inviato anche il segmento più breve.
4. Disattivare la rete, fermare, riattivare e usare Riprova. Verificare recupero dell'audio e TXT mancanti.
5. Provare chiamata, revoca microfono e forza arresto; verificare recupero dei `.part`, nessuna ripartenza abusiva. Ripetere scollegati dal caricatore/in risparmio energetico.

### File modificati per il ripristino grafico

- `../index.html`: aggancio all'adapter, riferimenti audio nativi nei progetti/bozze, isolamento dell'audio importato e menu modelli adattati alla larghezza del telefono.
- `../native-bridge.js`: controlli della schermata originale, sincronizzazione risultati senza duplicati, progetti, recupero ed export Android.
- `MainActivity.java`: WebView locale, bridge, permessi, import/export e impostazioni batteria.
- `RecordingService.java`: stesso servizio; aggiunti ID dell'ultima sessione e livello audio per la grafica.
- `app/build.gradle`: asset generati dal progetto originale, versione 1.1/package Pro.
- `app/src/main/AndroidManifest.xml`: nome/tema e gestione della rotazione.
- `app/src/main/assets/vendor/`: versioni già usate di jsPDF 2.5.1 e Chart.js 4.4.2.
- `../sw.js`: nuova cache con adapter per la versione web.
- `../tools/native-bridge-test.js`: regressioni di UI/servizio/progetti/export.
- `../.github/workflows/android-apk.yml`: build aggiornata, test web/adapter e firma di prova standard.

Riferimenti: [contenuti locali WebView](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content), [bridge JavaScript](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges), [servizio microphone](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
