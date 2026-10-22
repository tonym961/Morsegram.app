# Morsegram 0.1.9

**Le voci della barra in basso erano nero su nero.**

Stavo correggendo la chiave di colore sbagliata. La barra disegna testo e icone delle voci
non selezionate con `glass_tabUnselected`, che nel tema scuro valeva **#1C1C1C su un fondo
#242424**: contrasto **1,10 contro i 4,5 richiesti**, cioe' invisibili. Ora vale #B8B8B8,
7,83:1, e restano comunque secondarie rispetto all'ambra della voce attiva.

La barra entra anche nel controllo automatico del contrasto che gira a ogni rigenerazione
dei temi, cosi' il difetto non puo' ripresentarsi in silenzio.

Insieme a questa arrivano, finalmente visibili, le correzioni accumulate dalla 0.1.4:
fondo della barra grigio neutro invece che brunastro, e le otto coppie di colori del tema
chiaro che non reggevano il contrasto, fra cui l'ora sui messaggi inviati a 2,09 su 4,5.

Licenza GNU GPL v2 o successiva. Morsegram non e' affiliato a Telegram FZ-LLC.
