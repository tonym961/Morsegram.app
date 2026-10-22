#!/bin/zsh
# Dove sono finiti gli sblocchi locali: controllo sull'ARTEFATTO, non sui sorgenti.
#
# Uso: Tools/check-unlocks.sh play|sideload <file.apk|file.aab>
#   play      nessuna traccia deve esistere: sbloccare le funzioni a pagamento di un altro
#             servizio e' comportamento ingannevole per le policy di Google Play e farebbe
#             rimuovere l'app (vedi it/iotatec/morsegram/MorsegramEdition.java);
#   sideload  devono esserci tutte, ed e' questo il controllo positivo dell'altro: se la ricerca
#             non trova le tracce nemmeno nell'apk che le contiene, un "pulito" sul pacchetto per
#             Play non dimostra niente.
# A ogni rilascio si lanciano entrambi, in quest'ordine: prima sideload (la ricerca si dimostra
# sensibile), poi play (la ricerca non trova niente, e adesso vuol dire qualcosa). Legge sia un
# APK sia un AAB, perche' su Play si carica il bundle: il controllo va fatto sul file che si
# carica davvero, non su un APK costruito a parte che gli somiglia.
#
# Esiti: 0 l'atteso e' confermato, 1 l'atteso e' smentito, 2 CONTROLLO NON ESEGUITO (uso
# sbagliato, apk illeggibile, niente estratto, sentinella non trovata, elenco delle tracce vuoto
# o incompleto). Il 2 esiste per un errore vero del 22/10/2026: la prima versione di questo
# script faceva `cd` in una cartella temporanea prima di `unzip "$APK"`, percio' con l'apk
# indicato per percorso relativo unzip non trovava il file, l'errore era nascosto da
# 2>/dev/null, i glob non trovavano niente e il conteggio diceva "0 tracce trovate" su qualunque
# apk, sideload compreso: si auto-promuoveva. Un controllo che non sa dire di no e' peggio di
# nessun controllo. Da qui le tre regole: percorso assoluto prima di toccare le directory,
# nessun errore ingoiato, e un atteso positivo da confermare.
#
# Le tracce non sono scritte qui dentro: si leggono dall'albero (LocalUnlocks.java e
# src/sideload/res/values*/strings.xml), cosi' uno sblocco aggiunto domani entra nel controllo da
# solo, invece di passare inosservato perche' nessuno ha aggiornato un elenco scritto a mano.
set -u

ME="${0:t}"                                          # dentro una funzione $0 e' il nome della funzione
uso() { print -u2 "uso: $ME play|sideload <file.apk|file.aab>"; exit 2 }
nope() { print -u2 "    ERRORE: $1: controllo NON eseguito"; exit 2 }

ATTESO="${1:-}"; IN="${2:-}"
case "$ATTESO" in play|sideload) ;; *) uso ;; esac
[ -n "$IN" ] || uso
[ -f "$IN" ] || nope "apk non trovato ($IN)"

# percorso assoluto subito, prima di qualunque cd: vedi l'errore raccontato in testa
APK="$(cd -- "${IN:h}" 2>/dev/null && pwd)/${IN:t}"
[ -f "$APK" ] || nope "percorso non risolvibile ($IN)"
case "$APK" in
  *.apk) FORMATO=apk ;;
  *.aab) FORMATO=aab ;;                              # il bundle: tutto sotto base/
  *) nope "estensione non gestita ($IN): serve .apk o .aab" ;;
esac

RADICE="${0:A:h:h}"                                  # Tools/.. = Telegram/
UNLOCKS="$RADICE/TMessagesProj/src/main/java/it/iotatec/morsegram/unlock/LocalUnlocks.java"
SIDE_RES="$RADICE/TMessagesProj_App/src/sideload/res"
MAIN_RES="$RADICE/TMessagesProj_App/src/main/res"
for f in "$UNLOCKS" "$SIDE_RES/values/strings.xml" "$MAIN_RES/values/strings.xml" \
         "$MAIN_RES/values-it/strings.xml"; do
  [ -f "$f" ] || nope "sorgente mancante ($f)"
done

typeset -a bt; bt=( $HOME/Library/Android/sdk/build-tools/*/aapt2(Nn) )
AAPT2="${bt[-1]:-}"
# serve solo per un apk: le risorse di un aab si leggono da base/resources.pb (vedi sotto)
[ "$FORMATO" = aab ] || [ -x "$AAPT2" ] || nope "aapt2 non trovato nelle build-tools dell'SDK"

# -------------------------------------------------------------- cosa cercare, letto dall'albero
# 1) le chiavi di preferenza di LocalUnlocks.key(): finiscono nel dex come costanti di stringa
typeset -a chiavi
chiavi=( ${(f)"$(grep -o '"unlock\.[A-Za-z0-9_]*"' -- "$UNLOCKS" | tr -d '"' | sort -u)"} )
(( ${#chiavi} > 0 )) || nope "nessuna chiave unlock.* in LocalUnlocks.java"

# 2) nomi e testi delle stringhe dei soli sblocchi. Due sed sugli stessi file nello stesso ordine:
#    l'indice i lega il nome al suo testo senza dover inventare un separatore.
typeset -a nomi_riga testi
nomi_riga=( ${(f)"$(sed -n 's|.*<string name="\([^"]*\)">.*</string>.*|\1|p' "$SIDE_RES"/values*/strings.xml)"} )
testi=(     ${(f)"$(sed -n 's|.*<string name="[^"]*">\(.*\)</string>.*|\1|p' "$SIDE_RES"/values*/strings.xml)"} )
# una stringa su piu' righe sfuggirebbe alla sed senza dire niente: il conto deve tornare
attesi=$(grep -c '<string name=' "$SIDE_RES"/values*/strings.xml | awk -F: '{s+=$NF} END{print s+0}')
(( ${#nomi_riga} == attesi && ${#testi} == attesi )) \
  || nope "lette ${#nomi_riga} stringhe e ${#testi} testi su $attesi in $SIDE_RES/values*/strings.xml"
typeset -A testi_di
for i in {1..${#nomi_riga}}; do testi_di[${nomi_riga[i]}]+="${testi[i]}"$'\n'; done
typeset -a nomi; nomi=( ${(ou)nomi_riga} )

typeset -a testi_unici; testi_unici=( ${(u)testi} )
typeset -a cercate; cercate=( "${chiavi[@]}" "${nomi[@]}" "${testi_unici[@]}" )

# --------------------------------------------------------------------------- aprire l'artefatto
LAVORO="$(mktemp -d)" || nope "mktemp"
trap 'rm -rf "$LAVORO"' EXIT
APKDIR="$LAVORO/apk"
# tutto l'apk, non i soli dex: un testo puo' finire in un asset qualunque, e costa un secondo.
# Niente cd e niente errori ingoiati: "$APK" e' assoluto e -d dice a unzip dove scrivere.
unzip -q -o "$APK" -d "$APKDIR" || nope "unzip di $APK"
typeset -a dex loc
RADICEAPK="$APKDIR"; [ "$FORMATO" = aab ] && RADICEAPK="$APKDIR/base"
dex=( "$RADICEAPK"/dex/classes*.dex(Nn) "$RADICEAPK"/classes*.dex(Nn) )
loc=( "$RADICEAPK"/assets/localization_*.bin(Nn) )
(( ${#dex} > 0 )) || nope "nessun classes*.dex estratto da $APK"
(( ${#loc} > 0 )) || nope "nessun localization_*.bin estratto da $APK"
# Le risorse non si grepano in resources.arsc: i nomi delle risorse li' dentro sono UTF-16 e un
# grep di byte ASCII non li trovera' mai (un altro modo di non trovare niente). Si leggono con
# aapt2, che pero' non apre gli aab: nel bundle le risorse stanno in base/resources.pb, protobuf
# con i nomi in UTF-8, quindi quel file si legge direttamente (ed e' gia' dentro l'estratto).
RISORSE="$LAVORO/risorse.txt"
if [ "$FORMATO" = apk ]; then
  "$AAPT2" dump resources "$APK" > "$RISORSE" 2>"$LAVORO/aapt2.err" || nope "aapt2 dump resources"
  grep -q '^Package name=' "$RISORSE" || nope "aapt2 non ha letto le risorse di $APK"
else
  RISORSE="$RADICEAPK/resources.pb"
  [ -f "$RISORSE" ] || nope "base/resources.pb mancante in $APK"
fi

NOMERIS=resources.arsc; [ "$FORMATO" = aab ] && NOMERIS=resources.pb
breve() { sed "s|^$APKDIR/||; s|^$RISORSE\$|$NOMERIS|; s|^base/||; s|^dex/||; s|^assets/localization_||; s|\.bin\$||" }
dove() {                                             # <testo> <file...> -> dove compare
  local p="$1"; shift
  (( $# > 0 )) || return 0
  LC_ALL=C grep -l -a -F -e "$p" -- "$@" 2>/dev/null | breve | sort -u | tr '\n' ' '
}

print "=== ${APK:t}  (atteso: $ATTESO)"
VIA="aapt2 ${AAPT2:h:t}"; [ "$FORMATO" = aab ] && VIA="base/resources.pb"
print "    $FORMATO, $(du -h "$APK" | cut -f1 | tr -d ' '), $(find "$APKDIR" -type f | wc -l | tr -d ' ') file, ${#dex} dex, ${#loc} localizzazioni, risorse via $VIA"
print "    cercate ${#cercate} tracce: ${#chiavi} chiavi di preferenza, ${#nomi} nomi di stringa, ${#testi} testi"

# -------------------------------------------------------------------------------- sentinelle
# Dimostrano che la ricerca funziona su QUESTO apk, prima di fidarsi di un "non trovato". Sono
# tracce di Morsegram presenti in ENTRAMBE le edizioni, quindi vivono in src/main/res: se una
# manca, o l'apk non e' di Morsegram o la ricerca e' rotta, e in nessuno dei due casi si risponde.
SENT_EN="$(sed -n 's|.*<string name="MorsegramSettingsVoice">\(.*\)</string>.*|\1|p' "$MAIN_RES/values/strings.xml")"
SENT_IT="$(sed -n 's|.*<string name="MorsegramSettingsVoice">\(.*\)</string>.*|\1|p' "$MAIN_RES/values-it/strings.xml")"
[ -n "$SENT_EN" ] && [ -n "$SENT_IT" ] || nope "sentinella MorsegramSettingsVoice non piu' nei sorgenti"
# nel dex si cerca il nome del pacchetto, che resta in chiaro: i nomi delle classi no, in release
# R8 li offusca (nemmeno LocalUnlocks esiste piu' come nome), percio' le classi non sono tracce
[ -n "$(dove 'it/iotatec/morsegram' "${dex[@]}")" ] || nope "sentinella del dex non trovata in $APK"
s_en="$(dove "$SENT_EN" "${loc[@]}")"; s_it="$(dove "$SENT_IT" "${loc[@]}")"
[ -n "$s_en" ] || nope "sentinella inglese non trovata nelle localizzazioni di $APK"
[ -n "$s_it" ] || nope "sentinella italiana non trovata nelle localizzazioni di $APK"
grep -q -i 'morsegram' "$RISORSE" || nope "sentinella delle risorse non trovata in $APK"
print "    sentinelle: dex ok, localizzazioni ok (en in [${s_en% }], it in [${s_it% }]), risorse ok"

# ---------------------------------------------------------------------------------- la ricerca
# Un passaggio solo su tutto l'albero estratto per sapere QUALI file hanno dentro qualcosa, poi
# si attribuisce traccia per traccia nei soli file sospetti: 6000 file per 68 tracce sarebbero
# 68 passaggi, cosi' e' uno.
PATFILE="$LAVORO/tracce.txt"
print -lr -- "${cercate[@]}" > "$PATFILE"
typeset -a sospetti
sospetti=( ${(f)"$(LC_ALL=C grep -r -l -a -F -f "$PATFILE" "$APKDIR" 2>/dev/null)"} )
# il dump di aapt2 sta fuori dall'estratto e va aggiunto a mano; base/resources.pb e' gia' dentro
if [[ "$RISORSE" != "$APKDIR"/* ]]; then
  LC_ALL=C grep -q -a -F -f "$PATFILE" "$RISORSE" && sospetti+=( "$RISORSE" )
fi

typeset -a assenti nomi_non_costanti
trovate=0
for k in "${cercate[@]}"; do
  d="$(dove "$k" "${sospetti[@]}")"
  if [ -n "$d" ]; then
    trovate=$((trovate+1))
    [ "$ATTESO" = play ] && printf '  TROVATA   %-38s in [%s]\n' "${k:0:38}" "${d% }"
  else
    assenti+=( "$k" )
  fi
done

print "    tracce trovate: $trovate su ${#cercate}"
if [ "$ATTESO" = play ]; then
  (( trovate == 0 )) || { print "    ESITO: $trovate tracce degli sblocchi nel pacchetto per Play, NON pubblicare"; exit 1 }
  print "    ESITO: nessuna traccia degli sblocchi, e la ricerca che lo dice e' la stessa che sull'apk sideload le trova tutte"
  exit 0
fi

# atteso sideload: ogni chiave di preferenza e ogni testo devono esserci. Un nome di stringa puo'
# legittimamente non essere una costante del dex: MorsegramSettingsActivity compone quello della
# riga di spiegazione con unlockName(id) + "Info". In quel caso la traccia che conta e' il testo.
typeset -a gravi
for k in "${assenti[@]}"; do
  if [ -n "${testi_di[$k]:-}" ]; then                # e' un nome di stringa
    coperto=0
    for t in ${(f)testi_di[$k]}; do
      [ -n "$t" ] && [ -n "$(dove "$t" "${sospetti[@]}")" ] && coperto=1
    done
    if (( coperto )); then nomi_non_costanti+=( "$k" ); else gravi+=( "nome e testi assenti: $k" ); fi
  else
    gravi+=( "assente: ${k:0:80}" )
  fi
done
if (( ${#nomi_non_costanti} > 0 )); then
  print "    nomi non presenti come costante, ma col testo nell'apk: ${#nomi_non_costanti}"
  print "      ${nomi_non_costanti[*]}"
  print "      (attesi quelli con Info in coda, che la app compone a runtime; un altro nome qui"
  print "       dentro vuol dire che quella stringa non la chiama nessuno)"
fi
if (( ${#gravi} > 0 )); then
  for g in "${gravi[@]}"; do print "  $g"; done
  print "    ESITO: ${#gravi} tracce non arrivate nell'artefatto sideload: o lo sblocco non c'e', o la"
  print "    ricerca non lo vede, e in questo secondo caso il controllo sul pacchetto per Play"
  print "    non dimostra niente. Non si pubblica nessuna delle due edizioni finche' non torna."
  exit 1
fi
print "    ESITO: gli sblocchi sono nell'apk e la ricerca li vede: il controllo su Play ha valore"
exit 0
