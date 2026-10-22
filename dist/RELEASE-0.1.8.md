# Morsegram 0.1.8

**Le correzioni dei colori ora arrivano davvero sul telefono.**

Dalla 0.1.4 alla 0.1.7 ho corretto quattro volte la barra in basso e sullo schermo non e'
mai cambiato niente. La causa non erano i colori: i temi vengono copiati sul telefono al
primo avvio e poi non venivano **mai piu'** aggiornati. Ogni correzione restava dentro
l'APK e non raggiungeva il dispositivo.

Ora i temi hanno un numero di versione: quando cambiano, l'aggiornamento dell'app riscrive
i file. Con due cautele:

- **la tua scelta di tema non viene toccata**: se usavi un tema di Telegram resta quello;
- se stai usando un tema Morsegram, **i colori nuovi si vedono subito**, senza riavviare.

## Quindi, finalmente visibili

- **Barra in basso**: fondo grigio neutro invece che brunastro, ambra solo sulla voce
  selezionata (contrasto del testo scelto da 3,27 a 7,21).
- **Voci non selezionate**: dal 63% al 90% di opacita', testo e icone (da 6,92 a 12,90).
  Telegram le disegna tenui perche' il loro fondo e' semitrasparente; sul nostro opaco
  sparivano.
- **Otto coppie di colori** del tema chiaro che non reggevano il contrasto, fra cui l'ora
  sui messaggi inviati a 2,09 contro i 4,5 richiesti.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
