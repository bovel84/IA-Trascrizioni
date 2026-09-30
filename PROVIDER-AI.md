# Provider AI — VoiceScribe Pro 1.4

Il menu **Provider AI** aggiunge Ollama Cloud, NVIDIA NIM e OpenRouter a Groq per riassunti, analisi, grafici, mappe e chat. La registrazione nativa a schermo spento e la trascrizione Whisper usano ancora la chiave Groq nel riquadro sinistro.

## Uso sul telefono

1. Installa l'APK 1.4. Se Android rifiuta l'aggiornamento per firma diversa, esporta prima registrazioni e progetti e poi disinstalla la precedente VoiceScribe Pro. Le build di prova GitHub usano una firma temporanea; disinstallare cancella i dati privati dell'app.
2. Seleziona un provider nel riquadro destro, apri **Ottieni**, inserisci la relativa chiave API e premi **Salva Chiavi**.
3. Il catalogo si aggiorna automaticamente dopo **Salva Chiavi**, al cambio di provider con chiave salvata e all'apertura dell'app. Puoi ripetere l'aggiornamento con **Verifica i modelli disponibili**. La lista aggiornata resta salvata separatamente per provider. Scegli il modello o inserisci il suo ID esatto. Disponibilità, quote e costi dipendono dal tuo account presso il provider.
4. Scrivi una nota o apri una trascrizione; nella scheda AI prova un riassunto e una domanda. Ripeti con ogni provider. Il test reale richiede chiavi valide: i test automatici non consumano crediti e simulano le risposte.
5. Cambia provider e torna al precedente: chiave e modello devono essere ricordati separatamente. La chiave Groq Whisper deve restare disponibile per registrare.
6. Avvia una registrazione, blocca il telefono per almeno 3 minuti, parla, riapri l'app e ferma. Controlla audio e testo anche con un provider AI diverso da Groq selezionato.

Nell'APK le richieste AI passano direttamente da Android ai quattro endpoint HTTPS consentiti, con timeout, limiti di risposta, coda limitata e nessun inoltro delle credenziali attraverso redirect. L'audio continua nel servizio nativo separato. Non viene aggiunto alcun proxy o servizio intermedio. Le chiavi restano nel localStorage privato dell'app, come nella versione precedente; non esportarle nei progetti o nel codice.

Nella versione web le chiamate restano soggette al CORS imposto dai singoli provider: se il browser blocca una richiesta, usa l'APK. I modelli elencati dal catalogo potrebbero richiedere abilitazioni specifiche; un errore viene mostrato senza passare automaticamente a un altro servizio.

## Aggiornamento cataloghi ed errori 403

La versione 1.4 corregge gli alias Ollama destinati all'app o al terminale: `glm-5.3-flash:cloud` diventa `glm-5.3-flash` per l'API diretta. Lo stesso vale per il suffisso storico `-cloud` (ad esempio `gpt-oss:120b-cloud` diventa `gpt-oss:120b`). La correzione si applica ai modelli salvati, alla lista conservata e agli ID inseriti manualmente. Gli ID degli altri provider restano invariati. Per provare la correzione, seleziona Ollama, inserisci `glm-5.3-flash:cloud` nel campo modello e verifica che diventi `glm-5.3-flash`, poi richiedi un riassunto. Il nome corretto non garantisce autorizzazione o disponibilità nel proprio account.

Le risposte native di errore non JSON conservano ora il dettaglio come testo, senza renderizzare HTML. Un errore 403 persistente deve essere verificato nel portale Ollama in base al dettaglio ricevuto; l'app non aggira restrizioni del servizio.

Ollama usa il catalogo cloud ufficiale `https://ollama.com/api/tags`, letto come `models[].name`; la chat usa `https://ollama.com/v1/chat/completions`. Groq, NVIDIA e OpenRouter usano i rispettivi `/models`, letti come `data[].id`. I modelli disattivati e quelli dichiarati senza output testuale vengono esclusi. Una lista pubblica non prova che la chiave possa usare tutti i modelli: la risposta della richiesta di analisi resta determinante.

Sotto il pulsante di aggiornamento resta visibile il risultato o l'errore, compreso il provider e il dettaglio restituito dal servizio. HTTP 403 indica accesso negato: verifica nel portale del provider che la chiave sia del servizio selezionato, che l'account sia abilitato all'API e che il modello sia incluso nel tuo piano/permessi. L'app non può rimuovere un divieto imposto dal provider. HTTP 401 indica una chiave non accettata; HTTP 402 e 429 indicano problemi di credito o limiti di richieste. Non vengono fatti tentativi su provider diversi e le chiavi eventualmente ripetute nei dettagli del servizio vengono nascoste.

Per verificare la correzione, salva una chiave valida senza premere il pulsante manuale: la lista deve aggiornarsi e mostrare il conteggio. Cambia provider e torna al precedente: deve comparire il catalogo salvato, seguito dall'aggiornamento. Prova con una chiave non autorizzata: il dettaglio dell'errore deve restare leggibile, senza dichiarare riuscito l'aggiornamento. Una risposta tardiva per una vecchia chiave non deve sovrascrivere il risultato della nuova.

## File cambiati

- `index.html`: selezione provider, chiavi/modelli separati, migrazione delle impostazioni Groq esistenti, scelta modello libera e aggiornamento cataloghi, instradamento analisi/chat.
- `native-bridge.js`: richieste AI asincrone e risposte/errori dalla rete nativa Android.
- `android/app/src/main/java/it/voicescribe/recorder/AiHttp.java`: destinazioni fisse e richieste HTTPS senza redirect.
- `android/app/src/main/java/it/voicescribe/recorder/MainActivity.java`: bridge AI e worker separato dal recorder/export.
- `android/app/build.gradle`: versione 1.4, codice 5.
- `sw.js`: aggiornamento cache dell'interfaccia web.
- `tools/provider-test.js`, `android/app/src/test/java/it/voicescribe/recorder/AiHttpTest.java`, `.github/workflows/android-apk.yml`: verifiche provider incluse nella build GitHub.

## Verifiche

Eseguire `node tools/check-app.js`, `node tools/smoke-test.js`, `node tools/native-bridge-test.js`, `node tools/provider-test.js` e i test Android `:app:testDebugUnitTest`. Le prove coprono instradamento, separazione delle chiavi, cambio provider durante una richiesta, fallback JSON, errori di quota/autorizzazione, protezione delle destinazioni e flussi di registrazione esistenti. La preview grafica viene controllata a 390 × 844. Per microfono, sospensione e credenziali reali serve il test sul telefono indicato sopra.

## API ufficiali

- [Ollama Cloud](https://docs.ollama.com/cloud), [compatibilità OpenAI](https://docs.ollama.com/api/openai-compatibility): `https://ollama.com/v1`. Usa nomi cloud restituiti dall'API; non serve installare Ollama sul telefono.
- [NVIDIA NIM](https://docs.api.nvidia.com/nim/reference/llm-apis): `https://integrate.api.nvidia.com/v1`.
- [OpenRouter](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request): `https://openrouter.ai/api/v1`.
