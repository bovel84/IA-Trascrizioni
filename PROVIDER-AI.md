# Provider AI — VoiceScribe Pro 1.2

Il menu **Provider AI** aggiunge Ollama Cloud, NVIDIA NIM e OpenRouter a Groq per riassunti, analisi, grafici, mappe e chat. La registrazione nativa a schermo spento e la trascrizione Whisper usano ancora la chiave Groq nel riquadro sinistro.

## Uso sul telefono

1. Installa l'APK 1.2. Se Android rifiuta l'aggiornamento per firma diversa, esporta prima registrazioni e progetti e poi disinstalla la precedente VoiceScribe Pro. Le build di prova GitHub usano una firma temporanea; disinstallare cancella i dati privati dell'app.
2. Seleziona un provider nel riquadro destro, apri **Ottieni**, inserisci la relativa chiave API e premi **Salva Chiavi**.
3. Scegli il modello oppure premi **Verifica i modelli disponibili**. Puoi anche inserire l'ID esatto del modello nel campo dedicato. Disponibilità, quote e costi dipendono dal tuo account presso il provider.
4. Scrivi una nota o apri una trascrizione; nella scheda AI prova un riassunto e una domanda. Ripeti con ogni provider. Il test reale richiede chiavi valide: i test automatici non consumano crediti e simulano le risposte.
5. Cambia provider e torna al precedente: chiave e modello devono essere ricordati separatamente. La chiave Groq Whisper deve restare disponibile per registrare.
6. Avvia una registrazione, blocca il telefono per almeno 3 minuti, parla, riapri l'app e ferma. Controlla audio e testo anche con un provider AI diverso da Groq selezionato.

Nell'APK le richieste AI passano direttamente da Android ai quattro endpoint HTTPS consentiti, con timeout, limiti di risposta, coda limitata e nessun inoltro delle credenziali attraverso redirect. L'audio continua nel servizio nativo separato. Non viene aggiunto alcun proxy o servizio intermedio. Le chiavi restano nel localStorage privato dell'app, come nella versione precedente; non esportarle nei progetti o nel codice.

Nella versione web le chiamate restano soggette al CORS imposto dai singoli provider: se il browser blocca una richiesta, usa l'APK. I modelli elencati dal catalogo potrebbero richiedere abilitazioni specifiche; un errore viene mostrato senza passare automaticamente a un altro servizio.

## File cambiati

- `index.html`: selezione provider, chiavi/modelli separati, migrazione delle impostazioni Groq esistenti, scelta modello libera e aggiornamento cataloghi, instradamento analisi/chat.
- `native-bridge.js`: richieste AI asincrone e risposte/errori dalla rete nativa Android.
- `android/app/src/main/java/it/voicescribe/recorder/AiHttp.java`: destinazioni fisse e richieste HTTPS senza redirect.
- `android/app/src/main/java/it/voicescribe/recorder/MainActivity.java`: bridge AI e worker separato dal recorder/export.
- `android/app/build.gradle`: versione 1.2, codice 3.
- `sw.js`: aggiornamento cache dell'interfaccia web.
- `tools/provider-test.js`, `android/app/src/test/java/it/voicescribe/recorder/AiHttpTest.java`, `.github/workflows/android-apk.yml`: verifiche provider incluse nella build GitHub.

## Verifiche

Eseguire `node tools/check-app.js`, `node tools/smoke-test.js`, `node tools/native-bridge-test.js`, `node tools/provider-test.js` e i test Android `:app:testDebugUnitTest`. Le prove coprono instradamento, separazione delle chiavi, cambio provider durante una richiesta, fallback JSON, errori di quota/autorizzazione, protezione delle destinazioni e flussi di registrazione esistenti. La preview grafica viene controllata a 390 × 844. Per microfono, sospensione e credenziali reali serve il test sul telefono indicato sopra.

## API ufficiali

- [Ollama Cloud](https://docs.ollama.com/cloud), [compatibilità OpenAI](https://docs.ollama.com/api/openai-compatibility): `https://ollama.com/v1`. Usa nomi cloud restituiti dall'API; non serve installare Ollama sul telefono.
- [NVIDIA NIM](https://docs.api.nvidia.com/nim/reference/llm-apis): `https://integrate.api.nvidia.com/v1`.
- [OpenRouter](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request): `https://openrouter.ai/api/v1`.
