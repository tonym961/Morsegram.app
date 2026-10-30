# Morsegram 0.1.13

**Le menzioni ti raggiungono anche nelle chat silenziate, e si sentono.**

Chi sta in molti gruppi oggi ha una sola scelta: silenziare tutto, e perdere anche le
volte in cui qualcuno lo chiama per nome.

## Cosa abbiamo scoperto leggendo il codice

Telegram **gia' fa passare** le menzioni attraverso il silenzio di una chat: quando un
messaggio ti nomina, l'app non consulta nemmeno il silenziatore del gruppo. Quello che manca
non e' la comparsa della notifica, e' che si **senta**: con piu' di una chat in attesa, la
notifica della menzione finisce su un canale muto e il suono dipende dall'ultimo messaggio
arrivato, non dalla menzione.

Quindi non abbiamo rifatto quello che c'era: abbiamo aggiunto il controllo e la garanzia.

## Due categorie, non tre

**Menzioni e risposte** sono una categoria sola, e non e' una semplificazione: per Telegram
sono **lo stesso dato**. Nella notifica in arrivo c'e' un unico segnale che vale sia per
"ti hanno nominato" sia per "hanno risposto a un tuo messaggio", e separarli avrebbe
significato indovinare.

**Messaggi fissati**: l'interruttore esiste gia' in Telegram, e la pagina Morsegram si limita
a indicarti dov'e'. Non duplichiamo cio' che c'e'.

## Tre livelli

| Livello | Cosa fa |
|---|---|
| **Come Telegram** (predefinito) | nulla cambia, bit per bit, rispetto a oggi |
| **Sempre** | la menzione compare **e suona**, anche in una chat silenziata |
| **Mai** | la menzione non scavalca il silenzio della chat |

Livello generale in **Impostazioni → Morsegram → Notifiche**, piu' l'eccezione per singola
chat dove serve.

## Limiti dichiarati

- **Niente e' provato su un telefono vero**: l'emulatore non permette di accedere a un
  account, quindi ne' la push ne' il comportamento del silenzio sono stati verificati sul
  campo. E' la funzione che piu' di tutte va collaudata con l'uso.
- Se blocchi il canale Morsegram dalle impostazioni di Android, la notifica torna a
  comportarsi come quella di Telegram invece di sparire: nel dubbio si mostra.
- Su Android 7 e precedenti i canali non esistono: li' "Sempre" puo' solo far **comparire**
  la notifica, non farla suonare.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
