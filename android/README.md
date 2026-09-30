# VoiceScribe Audio per Android

## Perché serve una parte nativa

Il progetto originale è una PWA single-file (`index.html`), non un'app Android/Capacitor/React Native. Usa `getUserMedia`, `MediaRecorder`, IndexedDB e chiamate Groq. Il service worker conserva risorse offline: non registra il microfono e non mantiene vivi i timer della pagina. Il precedente audio artificiale non dava garanzie a schermo bloccato. Un **screen wake lock** impedisce lo spegnimento automatico soltanto finché la pagina è visibile; viene rilasciato quando la pagina è nascosta o il telefono viene bloccato.

Perciò la funzione richiesta è implementata in una piccola app nativa aggiuntiva, con interfaccia dedicata per registrazione/trascrizione. La PWA continua a offrire analisi, appunti e progetti. **La PWA installata dalla schermata Home non diventa l'app nativa** e non acquisisce registrazione affidabile a schermo spento. Gli archivi delle due app sono separati, senza sincronizzazione automatica. Il ZIP nativo contiene testo e segmenti WAV utilizzabili anche tramite Carica Audio nella PWA.

Riferimenti: [restrizioni di avvio Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [servizio microphone](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord), [Screen Wake Lock](https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API).

## Compilazione e installazione

1. Aprire questa cartella `android` in Android Studio, come progetto Gradle.
2. Usare **JDK 17** e **SDK Android 35**; il wrapper ufficiale incluso scarica Gradle 8.11.1, verificando il checksum della distribuzione. Il plugin Android è fissato a 8.9.2. Android Studio include normalmente il JDK necessario.
3. Sincronizzare il progetto, eseguire `:app:testDebugUnitTest`, poi Build → Build APK(s), oppure Run sul telefono collegato. Minimo Android 8 (API 26), target Android 15 (API 35).
4. Installare l'APK prodotto in `app/build/outputs/apk/debug/app-debug.apk`, oppure usare l'installazione dall'IDE.

Da terminale, con gli strumenti già configurati:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Stato della verifica su questo PC:** Java di sistema è 8; non risultano ambiente Android Studio/SDK completo o adb. Per verificare il codice sono stati scaricati solo nella cartella di lavoro il compilatore Eclipse ECJ, la piattaforma ufficiale Android 35 e JUnit: tutti i sorgenti Java compilano contro le API 35 senza errori/avvisi; i **3 test WAV passano**. La piattaforma e il wrapper Gradle sono stati verificati con SHA-256. La build Gradle completa dell'APK **non è stata eseguita**; nessun test su telefono è stato eseguito. Questi sono passaggi necessari prima di considerare verificato il comportamento a schermo bloccato.

## Comportamento e autorizzazioni

- Avvio solo con gesto nella schermata visibile, dopo consenso al microfono. Il servizio è `foregroundServiceType="microphone"`, non esportato, con `FOREGROUND_SERVICE` e `FOREGROUND_SERVICE_MICROPHONE`.
- Notifica persistente a bassa priorità, con apertura dell'app e **Ferma**. Su Android 13+ viene chiesto il permesso notifiche. Se negato, Android permette comunque il servizio ma lo mostra in App attive; il pulsante Ferma rimane nell'app. Non viene aggirata la scelta dell'utente.
- `AudioRecord` rimane nel servizio, non nell'Activity/WebView. Un thread legge PCM mono 16 bit/16 kHz e salva WAV di circa 120 secondi, senza riavviare il microfono tra i segmenti. Un segmento pesa circa 3,84 MB; un'ora circa 115 MB. Non c'è limite di durata prefissato: controllare spazio e batteria.
- La trascrizione Groq si esegue su un thread separato. Audio e risultati vengono conservati nella memoria privata dell'app. La chiave rimane in memoria, non in preferenze/file/backup; va reinserita dopo riapertura o rotazione della schermata se serve avviare/riprovare. Durante una sessione attiva resta nel servizio. L'audio viene inviato a Groq, come nella PWA.
- Un **partial CPU wake lock** è limitato alla sessione del servizio (cattura PCM su thread e completamento della coda); non tiene acceso lo schermo. Viene rilasciato su completamento, errore d'avvio e distruzione del servizio. Non c'è riproduzione silenziosa/artificiale.
- Pausa scarta i campioni e chiude il segmento; il microfono rimane aperto per poter riprendere dalla sessione autorizzata. I timestamp escludono le pause e sono relativi all'audio, per segmento.
- Timeout di connessione/lettura e retry per timeout, errori di connessione, HTTP 429/5xx. I WAV senza TXT sono la coda persistente. Se rete/Doze/risparmio energetico impediscono l'invio, la trascrizione può arrivare più tardi: **non è garantito l'aggiornamento remoto immediato** a schermo spento. Dopo Ferma la notifica può restare durante l'elaborazione dei segmenti accodati.
- Con **Riprova** nell'app visibile si recupera anche il segmento `.part` rimasto dopo una terminazione, riparando l'intestazione WAV. Il servizio non si riavvia automaticamente (`START_NOT_STICKY`), né al boot.
- Revoca del permesso, errore AudioRecord o silenziamento segnalato dal sistema su Android 10+ interrompono la registrazione e conservano i dati disponibili. L'app non forza accesso al microfono occupato da altre app. Le chiamate telefoniche vanno provate sul modello specifico.
- Il tasto Home, blocco schermo e rimozione della schermata dai recenti non chiedono al servizio di fermarsi (`stopWithTask=false`). Forza arresto, App attive → Arresta, spegnimento e alcune politiche dei produttori possono terminarlo: non vengono aggirati.
- Il pulsante impostazioni batteria apre le normali impostazioni dell'app. Non sono richiesti `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, permessi storage, accessibilità o esenzioni automatiche. Cambiare la gestione batteria solo se il test sul dispositivo mostra interruzioni.
- ZIP tramite selettore di documenti Android: WAV finalizzati + trascrizione con offset. Prima di esportare una sessione terminata forzatamente usare Riprova per recuperare l'ultimo `.part`. Disinstallazione/cancellazione dati elimina le sessioni: esportarle prima.

## Prova sul telefono (obbligatoria)

Usare frasi riconoscibili, ad esempio “inizio”, “telefono bloccato minuto tre”, “ritorno”. Annotare modello, versione Android, versione APK e impostazione batteria.

| Prova | Procedura | Esito atteso |
| --- | --- | --- |
| Permessi negati | Negare microfono; poi concederlo e negare notifiche | Nessuna cattura senza microfono; con notifiche negate avviso e servizio in App attive |
| Primo piano | Registrare parlando per oltre 4 minuti | Almeno due WAV, testo ordinato, offset circa 0/120/240 s |
| Background | Avviare, Home, usare un'altra app per 5 minuti parlando | Notifica presente; audio delle frasi pronunciate in background nei WAV/testo |
| Schermo bloccato | Avviare mentre visibile, bloccare per 10 minuti parlando ogni minuto, poi riaprire | Servizio attivo; nessun intervallo di silenzio o audio mancante dovuto al blocco; testo disponibile o segmenti pendenti dichiarati |
| Notifica | Bloccare e usare Ferma dalla notifica | Microfono rilasciato, ultimo WAV salvato, servizio termina dopo la coda |
| Pausa | Pausa a 110 s, attendere, riprendere e bloccare | Nessun audio durante la pausa; nuovi segmenti dopo Riprendi; offset senza pausa |
| Rete assente | Disattivare rete durante la registrazione bloccata; fermare, riattivare, Riprova | WAV conservati; TXT creati al recupero, senza duplicati per i segmenti già completati |
| Rotazione/recenti | Ruotare schermata e rimuovere dai recenti durante registrazione | Servizio non dipende dalla schermata; riaprendo compare la sessione |
| Chiamata/revoca | Ricevere una chiamata o revocare microfono mentre si registra | Nessun crash o ripartenza abusiva; interruzione/silenziamento rilevato dove segnalato, dati disponibili conservati |
| Arresto forzato | Forza arresto durante un segmento, riaprire, reinserire chiave e Riprova | Nessuna ripartenza automatica; segmento `.part` recuperato per quanto già scritto |
| Batteria/Doze | Ripetere prova 20–30 min scollegati dal caricatore, anche in risparmio energetico | Cattura verificata ascoltando WAV; eventuale ritardo rete non scambiato per perdita di registrazione |
| Export | Ferma, attendere completamento, esportare ZIP | WAV riproducibili e testo coerente con frasi prima/durante/dopo il blocco |

Per controllare il servizio con gli strumenti sviluppatore:

```powershell
adb shell dumpsys activity services it.voicescribe.recorder
adb shell dumpsys power
adb logcat -s AndroidRuntime AudioRecord ActivityManager
```

Verificare il servizio microphone attivo, il lock `VoiceScribe:Capture` durante la sessione e assente dopo l'arresto. Il solo testo nell'interfaccia non prova continuità: ascoltare anche i WAV esportati e confrontare la durata con la prova.

## File cambiati

- `../index.html`: rimozione audio artificiale e promessa “Schermo spento”, screen wake lock solo in pagina visibile, autosave in background, gestione track ended, rotazione basata sull'evento stop con protezione Ferma/Pausa e pulizia errori d'avvio.
- `../sw.js`: nuova versione cache per aggiornare la PWA.
- `../tools/dom-stub.js`, `../tools/smoke-test.js`: simulazione track ended e regressioni di rotazione/background/interruzione.
- `../README.md`: distinzione PWA/app Android e collegamento a questa guida.
- `settings.gradle`, `build.gradle`, `app/build.gradle`, `.gitignore`, `gradlew.bat`, `gradle/wrapper/*`: progetto Android separato, wrapper ufficiale e dipendenze minime.
- `app/src/main/AndroidManifest.xml`: permessi, Activity e servizio microphone.
- `MainActivity.java`: controlli, permessi, sessioni, recupero ed export ZIP.
- `RecordingService.java`: foreground service, cattura indipendente dallo schermo e coda di trascrizione.
- `WavFile.java`, `Transcriber.java`: WAV recuperabili e chiamate Groq con risultati persistenti.
- `app/src/test/.../WavFileTest.java`: 3 test JVM dell'intestazione WAV e del recupero dopo interruzione, eseguiti con successo; rieseguibili tramite Gradle.
