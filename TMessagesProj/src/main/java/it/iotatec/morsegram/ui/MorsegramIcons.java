/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.LauncherIconController;

import java.util.ArrayList;
import java.util.List;

/**
 * Selettore delle icone alternative, tutto nostro: zero righe upstream.
 *
 * <p>COME FUNZIONA. Il manifest di upstream dichiara sei {@code activity-alias} verso
 * {@code LaunchActivity} ({@code org.telegram.messenger.DefaultIcon}, {@code VintageIcon},
 * {@code AquaIcon}, {@code PremiumIcon}, {@code TurboIcon}, {@code NoxIcon}): uno solo e' abilitato
 * alla volta e il launcher mostra la sua icona. Non ne aggiungiamo di nostri — sarebbero invisibili a
 * {@code LauncherIconController.tryFixLauncherIconIfNeeded()} (chiamata da {@code ApplicationLoader}),
 * che riaccenderebbe {@code DefaultIcon} e metterebbe DUE voci sulla schermata iniziale. Riusiamo i
 * sei di upstream e sostituiamo, nel modulo app e alla stessa configurazione, le risorse che
 * indossano ({@code icon_2..icon_6_launcher*} e le anteprime {@code icon*_{back,fore}ground_sa}),
 * generate da {@code Tools/morsegram_assets_gen.py} job {@code launcher}. Risultato: nell'APK non
 * entra nessun aeroplanino di carta (ToS 2.4) e gli alias indossano il nostro segno "punto linea".
 *
 * <p>IL PAYWALL NON CI RIGUARDA, E NON LO AGGIRIAMO. {@code PremiumIcon}, {@code TurboIcon} e
 * {@code NoxIcon} hanno {@code premium = true} nell'enum di upstream, ma e' un'etichetta rimasta
 * attaccata a tre slot che adesso indossano le nostre risorse. Sotto la UI quel flag non lo guarda
 * nessuno e il lotto lo scriviamo noi (vedi {@link #apply(int, Runnable)}), quindi dal nostro selettore
 * tutte e sei le varianti sono libere senza toccare niente; nel selettore di upstream i due soli punti
 * che lo leggevano ({@code AppIconsSelectorCell}: il listener del tocco e {@code IconHolderView.bind})
 * passano da {@link #paywalled(LauncherIconController.LauncherIcon)}, che risponde sulla
 * <b>provenienza dell'artwork</b> e non sull'abbonamento: nessuna lettura di {@code UserConfig}, nessun
 * flag premium finto, due righe marcate e nient'altro.
 *
 * <p>LA SCELTA SOPRAVVIVE AL RIAVVIO da sola: lo stato abilitato/disabilitato dei componenti lo
 * tiene {@code PackageManager} nelle impostazioni del pacchetto, non noi. Per questo non c'e'
 * nessuna preferenza da tenere allineata: l'unica verita' e' {@code getComponentEnabledSetting},
 * che e' anche cio' che legge il launcher.
 *
 * <p>ANTEPRIME: {@code PackageManager.getActivityInfo(cn, MATCH_DISABLED_COMPONENTS).loadIcon()}
 * restituisce l'icona vera anche di un alias spento (API 24+), quindi niente id di risorsa del
 * modulo app da far entrare nella libreria.
 */
public final class MorsegramIcons {

    /** Gli slot nell'ordine in cui si mostrano; il primo e' quello predefinito. */
    private static final LauncherIconController.LauncherIcon[] ORDER = {
            LauncherIconController.LauncherIcon.DEFAULT,
            LauncherIconController.LauncherIcon.VINTAGE,
            LauncherIconController.LauncherIcon.AQUA,
            LauncherIconController.LauncherIcon.PREMIUM,
            LauncherIconController.LauncherIcon.TURBO,
            LauncherIconController.LauncherIcon.NOX,
    };

    /**
     * I nomi che legge l'utente. Sono chiavi NOSTRE: ridefinire {@code AppIconVintage} non servirebbe,
     * perche' con {@code BuildVars.USE_CLOUD_STRINGS} vince il language pack scaricato dai server di
     * Telegram (vedi il javadoc di {@code MorsegramBrand}).
     */
    private static final String[] NAMES = {
            "MorsegramIconAmber", "MorsegramIconNight", "MorsegramIconIvory",
            "MorsegramIconOutline", "MorsegramIconKey", "MorsegramIconEm",
    };

    private MorsegramIcons() {
    }

    /** Posizione della variante attiva; 0 (predefinita) se nessun alias risulta acceso. */
    public static int current() {
        for (int i = 0; i < ORDER.length; i++) {
            try {
                if (LauncherIconController.isEnabled(ORDER[i])) {
                    return i;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return 0;
    }

    /** Nome della variante attiva, per la riga delle impostazioni. */
    public static String currentName() {
        return LocaleController.getString(NAMES[current()]);
    }

    /**
     * Vero se il selettore di upstream deve ancora trattare lo slot come <b>artwork a pagamento di Telegram</b>,
     * cioe' se deve disegnarci il lucchetto e aprire il foglio Premium al tocco.
     *
     * <p>Per i sei slot di {@code ORDER} e' <b>falso</b>, i tre con {@code premium = true} compresi: le risorse
     * che indossano sono sostituite dalle nostre in {@code TMessagesProj_App/src/main/res} (vedi il javadoc della
     * classe), quindi nessuno di essi mostra piu' un'icona di Telegram e il lucchetto etichettava roba nostra.
     * <b>Non e' uno sblocco</b> e per questo non sta in {@code it.iotatec.morsegram.unlock.LocalUnlocks} e non e'
     * limitato all'edizione sideload: la sostituzione delle risorse e' in {@code src/main}, quindi gli slot sono
     * nostri in entrambe le edizioni e in entrambe l'etichetta sarebbe falsa. Non si finge nessun abbonamento:
     * qui non si legge {@code UserConfig}, non si tocca {@code MessagesController.premiumFeaturesBlocked()} e non
     * si chiede niente al server — si risponde soltanto che quell'artwork non e' di Telegram.
     *
     * <p>Fuori da {@code ORDER} si restituisce {@code icon.premium} tale e quale: se un riallineamento aggiungesse
     * un settimo alias, le sue risorse non sarebbero nostre e il lucchetto di upstream tornerebbe da solo.
     *
     * @param icon lo slot che upstream sta per disegnare; {@code null} non e' a pagamento (non si disegna niente)
     */
    public static boolean paywalled(LauncherIconController.LauncherIcon icon) {
        if (icon == null) {
            return false;
        }
        for (LauncherIconController.LauncherIcon nostra : ORDER) {
            if (nostra == icon) {
                return false;
            }
        }
        return icon.premium;
    }

    /** L'icona vera dello slot, anche se l'alias e' spento. Null se il sistema non la da'. */
    private static Drawable preview(Context context, int index) {
        try {
            final PackageManager pm = context.getPackageManager();
            final ComponentName cn = ORDER[index].getComponentName(context);
            final ActivityInfo info = pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS);
            return info.loadIcon(pm);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Elenco con anteprima vera, nome e pallino di selezione. */
    public static void show(BaseFragment fragment, Runnable onChanged) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int selected = current();
        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] box = new AlertDialog[1];
        for (int i = 0; i < ORDER.length; i++) {
            final int index = i;
            final View row = row(activity, index, index == selected);
            row.setOnClickListener(v -> {
                if (box[0] != null) {
                    box[0].dismiss();
                }
                if (index != current()) {
                    confirm(fragment, index, onChanged);
                }
            });
            list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        }
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAppIcon"));
        builder.setView(scroll);
        builder.setNegativeButton(LocaleController.getString("Cancel"), null);
        box[0] = builder.create();
        fragment.showDialog(box[0]);
    }

    private static View row(Context context, int index, boolean checked) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        row.setBackground(Theme.getSelectorDrawable(false));

        final ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final Drawable drawable = preview(context, index);
        if (drawable != null) {
            icon.setImageDrawable(drawable);
        }
        row.addView(icon, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

        final TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        name.setSingleLine(true);
        name.setText(LocaleController.getString(NAMES[index]));
        row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        final RadioButton radio = new RadioButton(context);
        radio.setSize(AndroidUtilities.dp(20));
        radio.setColor(Theme.getColor(Theme.key_dialogRadioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
        radio.setChecked(checked, false);
        row.addView(radio, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL));
        return row;
    }

    /**
     * L'avviso PRIMA del cambio: disabilitare l'alias attivo chiude l'app su parecchi launcher
     * (One UI, alcune ROM Xiaomi) nonostante {@code DONT_KILL_APP}, e le scorciatoie APPUNTATE alla
     * schermata iniziale possono perdere il collegamento (quelle dinamiche no: tutti gli alias
     * portano lo stesso {@code meta-data android.app.shortcuts}). Meglio dirlo che far sparire
     * l'app di sorpresa.
     */
    private static void confirm(BaseFragment fragment, int index, Runnable onChanged) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAppIconConfirmTitle"));
        builder.setMessage(LocaleController.formatString("MorsegramAppIconConfirm", 0, LocaleController.getString(NAMES[index])));
        builder.setPositiveButton(LocaleController.getString("MorsegramAppIconApply"), (dialog, which) -> apply(index, onChanged));
        builder.setNegativeButton(LocaleController.getString("Cancel"), null);
        fragment.showDialog(builder.create());
    }

    /**
     * Accende l'alias scelto e spegne gli altri cinque. Il sistema persiste lo stato da solo.
     *
     * <p>NON passa da {@code LauncherIconController.setIcon()}: quello itera
     * {@code LauncherIcon.values()} nell'ordine dell'enum e SPEGNE {@code DefaultIcon} (il primo) prima di
     * accendere il bersaglio, quindi fra le due chiamate binder esiste un istante con zero alias abilitati — e se
     * quella finestra non si chiude (processo ucciso, launcher che ricarica proprio lì) l'icona sparisce dalla
     * schermata iniziale. Qui il lotto lo scriviamo noi, sempre nel nostro file: zero righe upstream.
     *
     * <p>Su API 33+ una sola transazione ({@code setComponentEnabledSettings}): l'istante con zero alias accesi non
     * esiste e, se un componente non esistesse più per una rinomina upstream, la lista viene rifiutata INTERA senza
     * lasciare mezzo lavoro fatto. Sotto, si accende PRIMA il bersaglio: nel peggiore dei casi si vedono due icone
     * per un istante, mai zero.
     */
    public static void apply(int index, Runnable onChanged) {
        if (index < 0 || index >= ORDER.length) {
            return;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        final PackageManager pm = ctx.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                final List<PackageManager.ComponentEnabledSetting> lotto = new ArrayList<>(ORDER.length);
                for (int i = 0; i < ORDER.length; i++) {
                    lotto.add(new PackageManager.ComponentEnabledSetting(
                            ORDER[i].getComponentName(ctx),
                            i == index ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                       : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP));
                }
                pm.setComponentEnabledSettings(lotto);
            } else {
                pm.setComponentEnabledSetting(ORDER[index].getComponentName(ctx),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                for (int i = 0; i < ORDER.length; i++) {
                    if (i == index) {
                        continue;
                    }
                    try {
                        pm.setComponentEnabledSetting(ORDER[i].getComponentName(ctx),
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                    } catch (Throwable e) {
                        // un alias che non si spegne lascia DUE icone, non zero: si continua
                        FileLog.e(e);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (onChanged != null) {
            // rilegge getComponentEnabledSetting: se il lotto e' stato rifiutato la riga mostra lo stato vero
            onChanged.run();
        }
    }

    /** Diagnostica per logcat: quale alias risulta acceso al PackageManager. */
    public static String debugState() {
        final Context context = ApplicationLoader.applicationContext;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ORDER.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(ORDER[i].key).append('=')
                    .append(context == null ? "?" : context.getPackageManager().getComponentEnabledSetting(ORDER[i].getComponentName(context)));
        }
        return sb.toString();
    }
}
