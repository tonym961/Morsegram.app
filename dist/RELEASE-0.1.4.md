# Morsegram 0.1.4

L'assistente impara a lavorare **dentro il telefono**, e arrivano le correzioni che hai
chiesto usando l'app.

## L'assistente ora gira sul dispositivo

Riassunto, traduzione, bozze e ricerca possono usare il modello di Android (Gemini Nano),
che vive sul telefono: **niente chiavi, niente rete, niente testo che esce**. Su un
dispositivo supportato diventa la scelta predefinita, e Groq o Gemini restano una scelta
esplicita per il lavoro pesante.

Con il modello locale non servono le difese che servono al cloud: nessun consenso per
singola chat, nessuna anteprima, perche' non c'e' nessun destinatario. Le difese restano
intatte quando scegli un fornitore in rete: consenso per chat, anteprima del testo esatto
con gli identificatori gia' mascherati, e il nome della chat di partenza. In nessun caso
l'assistente lavora su una chat nascosta.

Se il modello locale non e' disponibile **l'app non passa al cloud di nascosto**: te lo
dice e ti lascia scegliere.

Dettagli: l'API di riassunto di Google supporta solo inglese, giapponese e coreano, quindi
usiamo quella generica, che le lingue le gestisce. La libreria pesa 178 KB sull'APK, e con
l'assistente locale spento sparisce del tutto.

## Correzioni d'uso

- **La barra in basso si legge.** Era semitrasparente e sfocava la lista dietro: con il
  tema ambra diventava marrone e le scritte sparivano. Ora e' opaca quando e' attivo un
  tema Morsegram.
- **Morsegram e' nelle Impostazioni di Telegram**, fra Notifiche e Dati, dove uno la
  cerca. Prima stava solo nel menu con i tre puntini della lista chat.
- **Blocca la modalita' vocale**: un tocco breve sul microfono non commuta piu' in
  videomessaggio. Nei gruppi dove i vocali sono vietati l'avviso continua ad apparire.

## Non fatto, e perche'

**Modalita' mancino**: rimandata. Specchiare la barra di scrittura significa intervenire in
ventidue punti dentro un file che Telegram riscrive quasi ogni mese. Il costo si pagherebbe
a ogni aggiornamento. Si fa, ma sapendo cosa costa.

## Provato e non provato

Provato: compilazione, installazione reale, avvio senza errori. **Non provato**: tutto
l'assistente a runtime, perche' l'emulatore non ha il modello di sistema e non si puo'
accedere a un account. Il blocco della modalita' vocale e' verificato per lettura.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
