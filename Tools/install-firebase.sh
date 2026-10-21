#!/bin/zsh
# Installa il google-services.json vero nei moduli che lo richiedono.
# Uso: tools/install-firebase.sh ~/Downloads/google-services.json
set -u
SRC="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$SRC" ] || { echo "uso: $0 <percorso/google-services.json>"; exit 2 }

# controlli prima di toccare il progetto
python3 -I - "$SRC" <<'PY' || exit 1
import json, sys
need = {"it.morsegram.app"}
optional = {"it.morsegram.app.beta"}
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print(f"  NON e' un JSON valido: {e}"); sys.exit(1)
pid = d.get("project_info", {}).get("project_id", "?")
pkgs = {c["client_info"]["android_client_info"]["package_name"] for c in d.get("client", [])}
print(f"  progetto Firebase: {pid}")
for p in sorted(pkgs): print(f"    app registrata: {p}")
if "placeholder" in pid:
    print("  ATTENZIONE: e' ancora il file segnaposto, non quello vero"); sys.exit(1)
missing = need - pkgs
if missing:
    print(f"  MANCA l'app principale: {', '.join(sorted(missing))}"); sys.exit(1)
absent = optional - pkgs
if absent:
    print(f"  manca il pacchetto di sviluppo ({', '.join(sorted(absent))}): le build di prova non avranno notifiche")
else:
    print("  tutti i pacchetti presenti")
PY

# Il modulo libreria ha namespace org.telegram.messenger (eredita' di upstream) e il plugin
# google-services pretende una voce per quel nome, anche se non viene mai pubblicato come app.
# Si aggiunge qui una voce di comodo, copiata da quella principale: evita di registrare su
# Firebase un nome pacchetto che non e' nostro, e a runtime vince comunque il modulo app.
TMP="$ROOT/build-firebase.json"
python3 -I - "$SRC" "$TMP" <<'PY'
import json, sys, copy
d = json.load(open(sys.argv[1]))
have = {c["client_info"]["android_client_info"]["package_name"] for c in d["client"]}
base = d["client"][0]
for pkg in ("org.telegram.messenger", "org.telegram.messenger.beta", "it.morsegram.app.web"):
    if pkg not in have:
        c = copy.deepcopy(base)
        c["client_info"]["android_client_info"]["package_name"] = pkg
        d["client"].append(c)
        print(f"  voce di comodo per {pkg} (non e' un'app pubblicata)")
json.dump(d, open(sys.argv[2], "w"), indent=2)
PY
[ -f "$TMP" ] || exit 1
for m in TMessagesProj TMessagesProj_App TMessagesProj_Wear TMessagesProj_AppStandalone TMessagesProj_AppHuawei TMessagesProj_AppHockeyApp; do
  [ -d "$ROOT/$m" ] && cp "$TMP" "$ROOT/$m/google-services.json" && echo "  installato in $m"
done
rm -f "$TMP"
echo "\nFatto. Ora ricompila:  tools/../../wear-build.sh :TMessagesProj_App:assembleAfatDebug -PMORSEGRAM_ABI=arm64-v8a"
echo "Il file e' pubblico (finisce dentro l'APK): si puo' committare."
echo "La chiave del service account NO: quella non entra mai nel repository."
