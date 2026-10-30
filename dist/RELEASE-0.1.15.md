# Morsegram 0.1.15

**L'app con il pollice sinistro, sei icone che sono nostre, e la corona che alza il volume.**

## L'invio passa a sinistra

**Impostazioni → Morsegram → Invio a sinistra.** Appena scrivi qualcosa il pulsante di invio
si sposta accanto al bordo sinistro del campo di testo: lo raggiungi con il pollice sinistro
senza attraversare lo schermo. Il campo si restringe di altrettanto, quindi il testo va a capo
un poco prima.

**Il microfono resta a destra, e non e' una dimenticanza.** Per annullare un vocale si trascina
il dito verso sinistra: con il microfono a sinistra non resterebbe la corsa che serve. Lo stesso
vale per il resto dell'apparato di registrazione, che e' tutto ancorato a quel gesto.

L'invio resta a destra anche mentre modifichi un messaggio, mentre registri, con un vocale
registrato in attesa, con un messaggio a tempo acceso, con una bozza impaginata aperta, mentre
cerchi con un bot in linea, e quando il campo resterebbe troppo stretto.

## Sei icone, e sono disegnate per Morsegram

**Impostazioni → Morsegram → Aspetto → Icona app**: Ambra (quella di sempre), Notte, Avorio, Contorno,
Tasto, Doppia linea. Nessuna ha il lucchetto: sono risorse che stanno nel telefono, non un
servizio che decide qualcun altro.

Con questo giro sono anche **uscite dal pacchetto le 25 immagini rimaste orfane** delle icone di
Telegram (gli aeroplanini di carta dei livelli adattivi): 65 file, 95 KB che prima viaggiavano
dentro l'app senza che nessuno li disegnasse piu'.

## Al polso: la corona alza il volume del vocale

Mentre ascolti un messaggio vocale sull'orologio, **la corona regola il volume** invece di far
scorrere la lista. Si accende da **Impostazioni → Corona per il volume** sull'orologio, e vale solo durante la
riproduzione: finito il vocale, la corona torna a scorrere.

## Limiti dichiarati, senza girarci intorno

- **La barra di scrittura non e' stata vista da nessuno.** Sta dietro l'accesso e l'emulatore non
  riesce ad accedere a un account: l'invio a sinistra e' verificato leggendo il codice e
  compilando, non guardando lo schermo. Dei cinque giri di revisione, ognuno ha trovato qualcosa
  che i precedenti non avevano visto, quindi e' ragionevole aspettarsi che qualcosa resti.
  Se trovi la pillola fuori posto, spegni l'interruttore: torna tutto dove era.
- **Il testo che vola** verso la bolla, appena premi invio, parte dalla posizione vecchia del
  campo: si vede uno scatto di circa mezzo centimetro. E' il prezzo di spostare il campo e non
  l'abbiamo pagato con altre righe dentro Telegram.
- **L'orologio**: l'app gira davvero su un OnePlus Watch 3 e l'accesso col codice QR funziona.
  La corona per il volume, pero', non e' stata ancora vista al polso: questo pacchetto non e'
  stato installato su quell'orologio, e sull'emulatore le impostazioni stanno dietro l'accesso.
- **Il pulsante "Accedi dal telefono" sull'orologio non funziona**, e non e' colpa di questo
  pacchetto: Telegram accetta un token d'accesso solo dentro l'app che l'ha chiesto, quindi un
  token creato dall'app ufficiale non vale per noi (`webauth_token_expired`). Usa **Codice QR**.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
