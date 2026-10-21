#!/bin/zsh
# Controllo di un artefatto prima di distribuirlo. Uso: tools/check-artifact.sh <file.apk|file.aab>
# Nasce da un errore reale: un APK costruito con -Pandroid.injected.build.abi finisce in
# build/intermediates/ ed e' marcato testOnly -> sul telefono dice "applicazione non installata"
# e Google Play rifiuta il bundle.
set -u
BT="$HOME/Library/Android/sdk/build-tools/36.0.0"
F="${1:-}"
[ -f "$F" ] || { echo "uso: $0 <file.apk|file.aab>"; exit 2 }
fail=0
say() { printf "%-28s %s\n" "$1" "$2" }

case "$F" in
  *.apk)
    if [[ "$F" == */intermediates/* ]]; then
      say "percorso" "SOSPETTO: viene da build/intermediates (output dell'iniezione IDE)"; fail=1
    else
      say "percorso" "ok (build/outputs)"
    fi
    if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$F" 2>/dev/null | grep -qi "testOnly"; then
      say "testOnly" "PRESENTE: il telefono rifiutera' l'installazione"; fail=1
    else
      say "testOnly" "assente"
    fi
    if "$BT/apksigner" verify --verbose "$F" 2>/dev/null | grep -q "v2 scheme (APK Signature Scheme v2): true"; then
      say "firma v2" "ok"
    else
      say "firma v2" "MANCANTE: Android 11+ non installa"; fail=1
    fi
    "$BT/zipalign" -c -P 16 -v 4 "$F" >/dev/null 2>&1 && say "allineamento 16 KB" "ok" || { say "allineamento 16 KB" "FALLITO"; fail=1 }
    say "pacchetto" "$("$BT/aapt2" dump badging "$F" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
    say "ABI native" "$(unzip -l "$F" | grep -oE 'lib/[^/]+/' | sort -u | tr -d 'lib/' | tr '\n' ' ')"
    ;;
  *.aab)
    if unzip -p "$F" base/manifest/AndroidManifest.xml 2>/dev/null | strings | grep -qi "testOnly"; then
      say "testOnly" "PRESENTE: Google Play rifiutera' il bundle"; fail=1
    else
      say "testOnly" "assente"
    fi
    unzip -l "$F" | grep -q "META-INF/.*\.RSA\|META-INF/.*\.SF" && say "firma" "presente" || { say "firma" "MANCANTE"; fail=1 }
    say "ABI native" "$(unzip -l "$F" | grep -oE 'base/lib/[^/]+/' | sort -u | sed 's|base/lib/||' | tr '\n' ' ')"
    ;;
  *) echo "estensione non gestita"; exit 2 ;;
esac
printf "%-28s %.1f MB\n" "dimensione" "$(( $(stat -f%z "$F") / 1048576.0 ))"
[ $fail -eq 0 ] && { echo "\nESITO: distribuibile"; exit 0 } || { echo "\nESITO: NON distribuire"; exit 1 }
