/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import android.util.TypedValue;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import it.iotatec.morsegram.AirplaneMode;
import it.iotatec.morsegram.LocalPins;
import it.iotatec.morsegram.hidden.HiddenChats;
import it.iotatec.morsegram.hidden.HiddenChatsActivity;
import it.iotatec.morsegram.hidden.HiddenLock;

/**
 * Pagina 'Morsegram' (voce nel menu '⋯' della lista chat via ApplicationLoaderImpl.addItemOptions, 0 righe upstream).
 * Sezioni: Connessione (modalità aereo, tile QS), Chat (conteggio pin locali, Chat nascoste con conteggio → sezione
 * protetta, Archivio nascosto = scorciatoia a SharedConfig.archiveHidden di upstream),
 * Assistente (interruttore, fornitori, chiavi API, chat attive, Segnalazioni dei contenuti generati),
 * Sblocchi locali (solo edizione sideload: una riga per ogni vincolo Premium che il client decide da solo e che qui
 * e' tolto, con il suo interruttore — acceso di default — e la spiegazione di cosa fa e di cosa resta fuori; vedi
 * it.iotatec.morsegram.unlock.LocalUnlocks),
 * Privacy (PIN Morsegram imposta/cambia/rimuovi, sblocco con impronta o volto, blocco automatico, blocca
 * ora, nota ToS: per gli altri le chat nascoste sono chat normali), Info (versione, client non ufficiale che usa le
 * API di Telegram — ToS 2.2 —, licenza). Stile DataSettingsActivity: RecyclerListView + celle upstream.
 * Tutte le stringhe per chiave da localization_*.bin (mai R.string: il namespace del modulo app non è visibile qui).
 */
public class MorsegramSettingsActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_SETTINGS = 2;
    private static final int TYPE_INFO = 3;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int connectionHeaderRow;
    private int airplaneRow;
    private int addTileRow;
    private int connectionInfoRow;
    private int chatHeaderRow;
    private int localPinsRow;
    private int hiddenChatsRow;
    private int archiveHiddenRow;
    private int lockVoiceModeRow;
    private int chatInfoRow;
    private java.util.List<it.iotatec.morsegram.fold.FoldSettings.Row> foldRows = java.util.Collections.emptyList();
    private int foldFirstRow = -1;
    private int voiceHeaderRow;
    private int voiceModeRow;
    private int voiceLangRow;
    private int voiceAllowSystemRow;
    private int voiceStatusRow;
    private int voiceInfoRow;
    private int aiHeaderRow;
    private int aiEnabledRow;
    /** Modello sul dispositivo: stato e scaricamento. Visibile anche con l'assistente spento, altrimenti un
     * telefono che deve ancora scaricare il modello non avrebbe nessuna via per farlo (l'interruttore nasce
     * spento proprio perche' il modello non c'e'). */
    private int aiLocalRow;
    /** Chi lavora adesso e perche': con il modello sul telefono, che non esce niente. */
    private int aiActiveInfoRow;
    private int aiPrimaryRow;
    private int aiFallbackRow;
    private int aiGroqKeyRow;
    private int aiGeminiKeyRow;
    private int aiActiveChatsRow;
    private int aiReportsRow;
    private int aiInfoRow;
    /**
     * Quante segnalazioni di contenuti generati ci sono. Tenuta in un campo perché AiReports legge un file e il
     * valore della riga serve a onBindViewHolder, cioè sul thread principale: si carica in fondo e si ridisegna.
     * -1 = non ancora letta, e in quel caso la riga non mostra un numero invece di mostrarne uno falso.
     */
    private int aiReportsCount = -1;
    private int updateHeaderRow;
    private int updateStatusRow;
    private int updateAutoRow;
    private int updateSourceRow;
    private int updateInfoRow;
    /**
     * Sezione "Sblocchi locali", visibile solo nell'edizione sideload (dove gli sblocchi esistono). Ogni voce ha la
     * sua riga, che dice in chiaro cosa fa e cosa resta fuori, e il suo interruttore: nascono accesi, perche' sono
     * il motivo per cui questa edizione esiste, ma chi preferisce l'aspetto originale li spegne uno per uno.
     * L'unica voce senza interruttore e' il quarto slot account, perche' il numero di slot si decide una volta
     * all'avvio. La fascia grigia chiude dicendo che tutto cio' che decidono i server di Telegram resta a pagamento
     * e che questa sezione non esiste nella versione dello Store.
     */
    private int unlocksHeaderRow;
    private int unlocksAccountsRow;
    /** Prima riga con interruttore: seguono le {@code LocalUnlocks.IDS} voci in ordine di identificatore. */
    private int unlocksFirstToggleRow;
    private int unlocksInfoRow;
    private int privacyHeaderRow;
    private int pinRow;
    private int biometricRow;
    private int autoLockRow;
    private int lockNowRow;
    private int removePinRow;
    private int privacyInfoRow;
    private int infoHeaderRow;
    private int versionRow;
    private int licenseInfoRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        connectionHeaderRow = rowCount++;
        airplaneRow = rowCount++;
        addTileRow = AirplaneMode.canRequestAddTile() ? rowCount++ : -1;
        connectionInfoRow = rowCount++;
        chatHeaderRow = rowCount++;
        localPinsRow = rowCount++;
        hiddenChatsRow = rowCount++;
        archiveHiddenRow = rowCount++;
        lockVoiceModeRow = rowCount++;
        chatInfoRow = rowCount++;
        foldRows = it.iotatec.morsegram.fold.FoldSettings.relevant() ? it.iotatec.morsegram.fold.FoldSettings.rows() : java.util.Collections.<it.iotatec.morsegram.fold.FoldSettings.Row>emptyList();
        foldFirstRow = foldRows.isEmpty() ? -1 : rowCount;
        rowCount += foldRows.size();
        final boolean voice = android.os.Build.VERSION.SDK_INT >= 33; // trascrizione on-device: SpeechRecognizer da file esiste da Android 13
        voiceHeaderRow = voice ? rowCount++ : -1;
        voiceModeRow = voice ? rowCount++ : -1;
        voiceLangRow = voice ? rowCount++ : -1;
        voiceAllowSystemRow = voice ? rowCount++ : -1;
        voiceStatusRow = voice ? rowCount++ : -1;
        voiceInfoRow = voice ? rowCount++ : -1;
        aiHeaderRow = rowCount++;
        aiEnabledRow = rowCount++;
        final boolean ai = it.iotatec.morsegram.ai.AiSettings.enabled();
        // visibile anche quando il modello non c'e': "non disponibile su questo telefono" e' un'informazione,
        // non un motivo per far sparire la riga
        aiLocalRow = it.iotatec.morsegram.ai.AiSettings.localShown() ? rowCount++ : -1;
        aiActiveInfoRow = ai ? rowCount++ : -1;
        aiPrimaryRow = ai ? rowCount++ : -1;
        aiFallbackRow = ai ? rowCount++ : -1;
        aiGroqKeyRow = ai ? rowCount++ : -1;
        aiGeminiKeyRow = ai ? rowCount++ : -1;
        // l'accensione per singola chat esiste per limitare cio' che ESCE: con il modello sul telefono non c'e'
        // niente da limitare e il conteggio direbbe zero mentre l'assistente funziona su ogni chat
        aiActiveChatsRow = ai && !it.iotatec.morsegram.ai.AiSettings.primaryIsLocal() ? rowCount++ : -1;
        aiReportsRow = ai ? rowCount++ : -1;
        aiInfoRow = rowCount++;
        final boolean upd = it.iotatec.morsegram.update.Updater.isSupported();
        updateHeaderRow = upd ? rowCount++ : -1;
        updateStatusRow = upd ? rowCount++ : -1;
        updateAutoRow = upd ? rowCount++ : -1;
        updateSourceRow = upd ? rowCount++ : -1;
        updateInfoRow = upd ? rowCount++ : -1;
        final boolean unlocks = it.iotatec.morsegram.unlock.LocalUnlocks.enabled();
        unlocksHeaderRow = unlocks ? rowCount++ : -1;
        unlocksAccountsRow = unlocks ? rowCount++ : -1;
        unlocksFirstToggleRow = unlocks ? rowCount : -1;
        if (unlocks) {
            rowCount += it.iotatec.morsegram.unlock.LocalUnlocks.IDS;
        }
        unlocksInfoRow = unlocks ? rowCount++ : -1;
        privacyHeaderRow = rowCount++;
        pinRow = rowCount++;
        final boolean hasPin = HiddenLock.hasPin();
        biometricRow = HiddenLock.canUseBiometric() ? rowCount++ : -1;
        autoLockRow = rowCount++;
        lockNowRow = hasPin && !HiddenLock.isLocked() ? rowCount++ : -1;
        removePinRow = hasPin ? rowCount++ : -1;
        privacyInfoRow = rowCount++;
        infoHeaderRow = rowCount++;
        versionRow = rowCount++;
        licenseInfoRow = rowCount++;
    }

    /** Identificatore della voce di {@code LocalUnlocks} alla posizione, o -1 se la riga non e' uno sblocco. */
    private int unlockId(int position) {
        if (unlocksFirstToggleRow == -1) {
            return -1;
        }
        final int id = position - unlocksFirstToggleRow;
        return id >= 0 && id < it.iotatec.morsegram.unlock.LocalUnlocks.IDS ? id : -1;
    }

    /**
     * Chiave della stringa che intitola la voce; la riga che spiega cosa fa e' la stessa chiave con "Info" in coda.
     * I testi vivono in {@code TMessagesProj_App/src/sideload/res/}, quindi nell'edizione per Play non esistono
     * nemmeno come stringa: per questo ogni chiamante e' dietro {@code LocalUnlocks.enabled()}, che li' e' una
     * costante falsa e fa cancellare a R8 questo metodo con tutte le chiavi dentro.
     */
    private static String unlockName(int id) {
        switch (id) {
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_CHAT_TRANSLATE: return "MorsegramUnlockTranslate";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_PREMIUM_STICKERS: return "MorsegramUnlockStickers";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_EMOJI_CATEGORIES: return "MorsegramUnlockEmojiCategories";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_LOCKED_FOLDERS: return "MorsegramUnlockFolders";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_AVATAR_GRADIENTS: return "MorsegramUnlockAvatarGradients";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_AVATAR_CUSTOM_GRADIENT: return "MorsegramUnlockAvatarCustom";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_NOTE_LINKS: return "MorsegramUnlockNoteLinks";
            case it.iotatec.morsegram.unlock.LocalUnlocks.ID_BIO_LINKS: return "MorsegramUnlockBioLinks";
            default: return "";
        }
    }

    /** Riga della sezione Pieghevole per la posizione, o null. */
    private it.iotatec.morsegram.fold.FoldSettings.Row foldRow(int position) {
        final int i = position - foldFirstRow;
        return foldFirstRow != -1 && i >= 0 && i < foldRows.size() ? foldRows.get(i) : null;
    }

    /** Dopo PIN/blocco cambiati: le righe condizionali (Blocca ora, Rimuovi PIN) appaiono o spariscono. */
    private void refresh() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        loadAiReportsCount();
    }

    /** Il conteggio delle segnalazioni viene da un file: si legge in fondo e si ridisegna solo se è cambiato. */
    private void loadAiReportsCount() {
        if (aiReportsRow == -1) {
            return;
        }
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final int count = it.iotatec.morsegram.ai.AiReports.count();
            AndroidUtilities.runOnUIThread(() -> {
                if (count != aiReportsCount) {
                    aiReportsCount = count;
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                }
            });
        });
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString("MorsegramSettings"));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        if (parentLayout != null && parentLayout.isRightLayout()) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_close);
        }

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == airplaneRow) {
                AirplaneMode.toggle();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(AirplaneMode.isOn());
                }
            } else if (position == addTileRow) {
                AirplaneMode.requestAddTile(getParentActivity());
            } else if (position == hiddenChatsRow) {
                HiddenChatsActivity.open(this);
            } else if (position == archiveHiddenRow) {
                // scorciatoia all'impostazione upstream (SharedConfig.archiveHidden, globale e persistente): stesso
                // effetto della pressione lunga su 'Archivio' nella lista chat. 0 righe upstream: la lista rilegge lo
                // stato in DialogsActivity.createView, quindi il cambio si vede alla prossima apertura della lista.
                SharedConfig.toggleArchiveHidden();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SharedConfig.archiveHidden);
                }
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            } else if (position == lockVoiceModeRow) {
                final boolean on = !it.iotatec.morsegram.voice.VoiceSettings.recordModeLocked();
                it.iotatec.morsegram.voice.VoiceSettings.setRecordModeLocked(on);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
            } else if (position == pinRow) {
                onPinClick();
            } else if (position == biometricRow) {
                final boolean enabled = !HiddenLock.isBiometricEnabled();
                HiddenLock.setBiometricEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
            } else if (position == autoLockRow) {
                onAutoLockClick();
            } else if (position == lockNowRow) {
                HiddenLock.lock();
                refresh();
                showBulletin(R.raw.passcode_lock_close, LocaleController.getString("MorsegramLocked"));
            } else if (position == removePinRow) {
                onRemovePinClick();
            } else if (position == voiceModeRow && getParentActivity() != null) {
                showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), it.iotatec.morsegram.voice.VoiceSettings.modeLabels(), LocaleController.getString("MorsegramVoiceMode"), it.iotatec.morsegram.voice.VoiceSettings.mode(), (d, which) -> { it.iotatec.morsegram.voice.VoiceSettings.setMode(which); refresh(); }));
            } else if (position == voiceLangRow && getParentActivity() != null) {
                showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), it.iotatec.morsegram.voice.VoiceSettings.languageChoiceLabels(), LocaleController.getString("MorsegramVoiceLanguage"), it.iotatec.morsegram.voice.VoiceSettings.languageChoiceIndex(), (d, which) -> { it.iotatec.morsegram.voice.VoiceSettings.setLanguageChoice(which); refresh(); }));
            } else if (position == voiceAllowSystemRow) {
                final boolean on = !it.iotatec.morsegram.voice.VoiceSettings.allowSystem();
                it.iotatec.morsegram.voice.VoiceSettings.setAllowSystem(on);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
            } else if (position == aiEnabledRow) {
                final boolean on = !it.iotatec.morsegram.ai.AiSettings.enabled();
                it.iotatec.morsegram.ai.AiSettings.setEnabled(on);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
                refresh();
            } else if (position == aiLocalRow) {
                onAiLocalClick();
            } else if (position == aiPrimaryRow && getParentActivity() != null) {
                showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), it.iotatec.morsegram.ai.AiSettings.providerLabels(), LocaleController.getString("MorsegramAiPrimary"), it.iotatec.morsegram.ai.AiSettings.primary(), (d, which) -> { it.iotatec.morsegram.ai.AiSettings.setPrimary(which); refresh(); }));
            } else if (position == aiFallbackRow && getParentActivity() != null) {
                showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), it.iotatec.morsegram.ai.AiSettings.fallbackLabels(), LocaleController.getString("MorsegramAiFallback"), it.iotatec.morsegram.ai.AiSettings.fallbackIndex(), (d, which) -> { it.iotatec.morsegram.ai.AiSettings.setFallback(it.iotatec.morsegram.ai.AiSettings.fallbackIdForIndex(which)); refresh(); }));
            } else if (position == aiGroqKeyRow) {
                onAiKeyClick(it.iotatec.morsegram.ai.AiProvider.GROQ);
            } else if (position == aiGeminiKeyRow) {
                onAiKeyClick(it.iotatec.morsegram.ai.AiProvider.GEMINI);
            } else if (position == aiReportsRow) {
                presentFragment(new it.iotatec.morsegram.ai.AiReportsActivity());
            } else if (position == updateStatusRow) {
                onUpdateStatusClick();
            } else if (position == updateAutoRow) {
                final boolean on = !it.iotatec.morsegram.update.Updater.autoCheckEnabled();
                it.iotatec.morsegram.update.Updater.setAutoCheckEnabled(on);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
            } else if (position == updateSourceRow) {
                onUpdateSourceClick();
            } else if (position == voiceStatusRow) {
                it.iotatec.morsegram.voice.LocalTranscriber.resetProbe();
                refresh();
            } else if (it.iotatec.morsegram.unlock.LocalUnlocks.enabled() && unlockId(position) != -1) {
                // la guardia su enabled() non e' pleonastica: tiene la chiamata a set() dentro un ramo che R8
                // cancella nell'edizione per Play, dove enabled() e' una costante falsa, e con essa le chiavi
                final int id = unlockId(position);
                final boolean on = !it.iotatec.morsegram.unlock.LocalUnlocks.on(id);
                it.iotatec.morsegram.unlock.LocalUnlocks.set(id, on);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(on);
                }
                // DialogFilter.locked e' uno stato in memoria: lockFiltersInternal lo accende, ma con lo sblocco
                // attivo la sua guardia esce subito e non lo spegne. Riaccendendo lo sblocco va quindi azzerato qui,
                // altrimenti i lucchetti accesi in questa sessione resterebbero fino al riavvio. 0 righe upstream
                if (id == it.iotatec.morsegram.unlock.LocalUnlocks.ID_LOCKED_FOLDERS) {
                    if (on) {
                        for (org.telegram.messenger.MessagesController.DialogFilter filter : getMessagesController().getDialogFilters()) {
                            filter.locked = false;
                        }
                    } else {
                        getMessagesController().lockFiltersInternal();
                    }
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                }
            } else {
                final it.iotatec.morsegram.fold.FoldSettings.Row fold = foldRow(position);
                if (fold != null && fold.type == it.iotatec.morsegram.fold.FoldSettings.ROW_CHECK) {
                    final boolean on = fold.toggle();
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(on);
                    }
                }
            }
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // lo stato può essere cambiato dalla tile/notifica o dall'auto-lock mentre la pagina era coperta
        refresh();
        // la sonda del modello sul dispositivo (una chiamata binder verso AICore) gira su coda di fondo e può
        // tornare mentre la pagina è aperta: senza questo ascoltatore la riga resterebbe "controllo in corso"
        it.iotatec.morsegram.ai.AiGate.addListener(aiListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        it.iotatec.morsegram.ai.AiGate.removeListener(aiListener);
    }

    @Override
    public void onFragmentDestroy() {
        it.iotatec.morsegram.ai.AiGate.removeListener(aiListener);
        // lo scaricamento del modello va avanti (lo fa Android), ma non deve trattenere questa pagina
        it.iotatec.morsegram.ai.AiSettings.localForgetDownload(aiDownloadListener);
        super.onFragmentDestroy();
    }

    private final it.iotatec.morsegram.ai.AiGate.Listener aiListener = this::refresh;

    /* ====================================================================================== privacy */

    /** Imposta il PIN (dialogo a due campi) oppure, se c'è già, lo cambia dopo lo sblocco. */
    private void onPinClick() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        if (HiddenLock.hasPin()) {
            HiddenLock.authenticate(activity, () -> HiddenLock.setupPin(activity, () -> {
                refresh();
                showBulletin(0, LocaleController.getString("MorsegramPinSaved"));
            }, null), null);
        } else {
            HiddenLock.setupPin(activity, () -> {
                refresh();
                showBulletin(0, LocaleController.getString("MorsegramPinSaved"));
            }, null);
        }
    }

    /**
     * Rimuove il PIN dopo lo sblocco e una conferma: le chat restano nascoste dalle liste ma non più protette.
     * Con chat nascoste esistenti la conferma lo dice senza giri di parole (resteranno aperte a chiunque abbia il
     * telefono); la riga delle chat nascoste e quella del PIN lo ripetono dopo la rimozione.
     */
    private void onRemovePinClick() {
        final Activity activity = getParentActivity();
        if (activity == null || !HiddenLock.hasPin()) {
            return;
        }
        HiddenLock.authenticate(activity, () -> {
            if (getParentActivity() == null) {
                return;
            }
            final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString("MorsegramRemovePin"));
            builder.setMessage(LocaleController.getString(hiddenChatsCount() > 0 ? "MorsegramRemovePinConfirmHidden" : "MorsegramRemovePinConfirm"));
            builder.setPositiveButton(LocaleController.getString("MorsegramRemovePin"), (d, which) -> {
                HiddenLock.clearPin();
                refresh();
                showBulletin(0, LocaleController.getString("MorsegramPinRemoved"));
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            final AlertDialog dialog = builder.create();
            showDialog(dialog);
            final View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button instanceof android.widget.TextView) {
                ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
            }
        }, null);
    }

    /** Selettore del timeout di blocco automatico (valori di HiddenLock.TIMEOUTS, etichette di timeoutLabel). */
    private void onAutoLockClick() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final int[] values = HiddenLock.TIMEOUTS;
        final String[] labels = new String[values.length];
        int selected = -1;
        final int current = HiddenLock.timeoutSeconds();
        for (int i = 0; i < values.length; i++) {
            labels[i] = HiddenLock.timeoutLabel(values[i]);
            if (values[i] == current) {
                selected = i;
            }
        }
        final Dialog dialog = AlertsCreator.createSingleChoiceDialog(activity, labels, LocaleController.getString("MorsegramAutoLock"), selected, (d, which) -> {
            if (which >= 0 && which < values.length) {
                HiddenLock.setTimeoutSeconds(values[which]);
                refresh();
            }
        });
        showDialog(dialog);
    }

    private void showBulletin(int rawRes, String text) {
        try {
            if (!BulletinFactory.canShowBulletin(this)) {
                return;
            }
            if (rawRes != 0) {
                BulletinFactory.of(this).createSimpleBulletin(rawRes, text).show();
            } else {
                BulletinFactory.of(this).createSuccessBulletin(text).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* ======================================================================================== valori */

    private int localPinsCount() {
        return LocalPins.count(currentAccount);
    }

    private int hiddenChatsCount() {
        return HiddenChats.count(currentAccount);
    }

    /* ================================================================================ aggiornamenti */

    /** Testo della riga di stato: versione installata, aggiornamento trovato, avanzamento o errore. */
    private String updateStatusText() {
        final it.iotatec.morsegram.update.Updater.Found f = it.iotatec.morsegram.update.Updater.available();
        switch (it.iotatec.morsegram.update.Updater.state()) {
            case it.iotatec.morsegram.update.Updater.STATE_CHECKING:
                return LocaleController.getString("MorsegramUpdateChecking");
            case it.iotatec.morsegram.update.Updater.STATE_DOWNLOADING:
                return LocaleController.formatString("MorsegramUpdateDownloading", 0, it.iotatec.morsegram.update.Updater.progress());
            case it.iotatec.morsegram.update.Updater.STATE_AVAILABLE:
            case it.iotatec.morsegram.update.Updater.STATE_READY:
                return f == null ? "" : LocaleController.formatString("MorsegramUpdateAvailable", 0, f.version);
            case it.iotatec.morsegram.update.Updater.STATE_ERROR:
                return LocaleController.getString(updateErrorKey(it.iotatec.morsegram.update.Updater.lastError()));
            case it.iotatec.morsegram.update.Updater.STATE_UP_TO_DATE:
            default:
                return LocaleController.formatString("MorsegramUpdateUpToDate", 0, it.iotatec.morsegram.update.Updater.currentVersion());
        }
    }

    /** Gli errori dell'updater hanno un testo dedicato: una firma diversa non e' un errore qualsiasi. */
    private static String updateErrorKey(String error) {
        if ("bad_signature".equals(error)) {
            return "MorsegramUpdateBadSignature";
        } else if ("bad_hash".equals(error)) {
            return "MorsegramUpdateBadHash";
        } else if ("no_source".equals(error)) {
            return "MorsegramUpdateNoSource";
        } else if ("no_account".equals(error)) {
            return "MorsegramUpdateNoAccount";
        }
        return "MorsegramUpdateFailed";
    }

    /** Un tocco: se c'e' un aggiornamento lo scarica e installa, altrimenti controlla. */
    private void onUpdateStatusClick() {
        final int state = it.iotatec.morsegram.update.Updater.state();
        if (state == it.iotatec.morsegram.update.Updater.STATE_AVAILABLE) {
            it.iotatec.morsegram.update.Updater.downloadAndInstall(getParentActivity());
        } else if (state != it.iotatec.morsegram.update.Updater.STATE_CHECKING && state != it.iotatec.morsegram.update.Updater.STATE_DOWNLOADING) {
            it.iotatec.morsegram.update.Updater.checkNow(true);
        }
        refresh();
    }

    /** Chat da cui arrivano gli aggiornamenti: "me" (Messaggi salvati) oppure un canale. */
    private void onUpdateSourceClick() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramUpdateSource"));
        final EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintText(LocaleController.getString("MorsegramUpdateSourceHint"));
        field.setSingleLine(true);
        field.setText(it.iotatec.morsegram.update.Updater.source());
        field.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        builder.setView(field);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            it.iotatec.morsegram.update.Updater.setSource(field.getText() == null ? "" : field.getText().toString());
            refresh();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /* ===================================================================================== assistente */

    /**
     * Inserimento della chiave API di un fornitore. Il campo e' MASCHERATO e parte VUOTO: la chiave salvata non
     * viene mai rimostrata, nemmeno a chi ha il telefono in mano. Il salvataggio passa da AiKeys (cifratura con
     * l'Android Keystore) e, se il Keystore non funziona, FALLISCE: non si scrive una chiave in chiaro.
     * La cifratura gira su un thread di fondo: vedi il commento dentro al pulsante Salva.
     */
    /**
     * La riga del modello sul dispositivo: si spiega cosa e', e lo scaricamento parte solo da qui. Non c'e'
     * nessuna finestra di consenso di sistema dietro {@code download()}, quindi questa e' l'unica occasione in
     * cui l'utente decide; se dice no, lo si ricorda e non gli si richiede a ogni funzione.
     */
    private void onAiLocalClick() {
        if (getParentActivity() == null) {
            return;
        }
        final boolean ready = it.iotatec.morsegram.ai.AiSettings.localReady();
        final boolean downloading = it.iotatec.morsegram.ai.AiSettings.localDownloading();
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramAiLocalTitle"));
        builder.setMessage(LocaleController.getString("MorsegramAiLocalExplain") + "\n\n"
                + LocaleController.getString("MorsegramAiLocalModel") + ": " + it.iotatec.morsegram.ai.AiSettings.localStatusText()
                + (downloading ? "\n\n" + LocaleController.getString("MorsegramAiLocalDownloadingNow") : ""));
        if (ready) {
            // la prefix caching scrive file di cache nello storage privato dell'app: e' giusto poterli buttare
            builder.setPositiveButton(LocaleController.getString("MorsegramAiLocalClearCache"), (d, which) ->
                    it.iotatec.morsegram.ai.AiSettings.localClearCaches(() ->
                            showBulletin(0, LocaleController.getString("MorsegramAiLocalCacheCleared"))));
        } else if (downloading) {
            // nessun pulsante: localNeedsDownload() e' vero anche MENTRE si scarica, e OnDeviceModel.download()
            // in quel caso esce subito senza agganciare il nuovo ascoltatore. Il pulsante Scarica sarebbe un
            // tocco senza effetto, senza avanzamento e senza un messaggio: lo stato lo dice il testo qui sopra.
        } else if (it.iotatec.morsegram.ai.AiSettings.localNeedsDownload()) {
            builder.setPositiveButton(LocaleController.getString("MorsegramAiLocalDownloadNow"), (d, which) -> onAiLocalDownload());
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        showDialog(builder.create());
    }

    /**
     * Lo scaricamento: il peso vero lo dice il dispositivo quando parte, e si mostra nella riga.
     *
     * <p>L'ascoltatore resta vivo per tutta la durata (gigabyte, quindi minuti), quindi lo si tiene in un campo
     * e lo si fa dimenticare in {@link #onFragmentDestroy()}: senza quello, questa pagina resterebbe appesa
     * all'oggetto di download fino alla fine.</p>
     */
    private void onAiLocalDownload() {
        aiDownloadListener = new it.iotatec.morsegram.ai.OnDeviceModel.DownloadListener() {
            @Override
            public void onStarted(long totalBytes) {
                showBulletin(0, LocaleController.formatString("MorsegramAiLocalDownloadSize", 0, AndroidUtilities.formatFileSize(totalBytes)));
                refresh();
            }

            @Override
            public void onProgress(long bytes, long totalBytes) {
            }

            @Override
            public void onCompleted() {
                showBulletin(0, LocaleController.getString("MorsegramAiLocalDownloadDone"));
                refresh();
            }

            @Override
            public void onFailed(String detail) {
                showBulletin(0, LocaleController.getString("MorsegramAiLocalDownloadFailed"));
                refresh();
            }
        };
        it.iotatec.morsegram.ai.AiSettings.localDownload(aiDownloadListener);
        refresh();
    }

    private it.iotatec.morsegram.ai.OnDeviceModel.DownloadListener aiDownloadListener;

    private void onAiKeyClick(int providerId) {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final boolean has = it.iotatec.morsegram.ai.AiKeys.hasKey(providerId);
        final EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintText(LocaleController.getString(has ? "MorsegramAiKeyReplaceHint" : "MorsegramAiKeyHint"));
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        field.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.formatString("MorsegramAiKeyOf", 0, it.iotatec.morsegram.ai.AiProvider.labelOf(providerId)));
        builder.setView(field);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            final String typed = field.getText() == null ? "" : field.getText().toString().trim();
            if (typed.length() == 0) {
                return;
            }
            // il salvataggio e' la piu' costosa delle due operazioni di AiKeys: carica l'AndroidKeyStore e, alla
            // PRIMA chiave, genera nel TEE una AES-256. Sono chiamate verso keystore2 che su dispositivi lenti
            // costano centinaia di ms fino a oltre un secondo: sul thread UI sarebbe il frame bloccato (e in
            // debug StrictMode lo segnala). Va in fondo, e sulla UI torna solo l'esito.
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                final boolean ok = it.iotatec.morsegram.ai.AiKeys.setKey(providerId, typed);
                AndroidUtilities.runOnUIThread(() -> {
                    refresh();
                    showBulletin(0, LocaleController.getString(ok ? "MorsegramAiKeySaved" : "MorsegramAiKeystoreFailed"));
                });
            });
        });
        if (has) {
            builder.setNeutralButton(LocaleController.getString("MorsegramAiKeyRemove"), (dialog, which) ->
                    // clear() scrive solo una preferenza, ma passa dallo stesso file dei segreti: stesso
                    // trattamento del salvataggio, cosi' le due operazioni non si incrociano fra thread diversi
                    org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                        it.iotatec.morsegram.ai.AiKeys.clear(providerId);
                        AndroidUtilities.runOnUIThread(() -> {
                            refresh();
                            showBulletin(0, LocaleController.getString("MorsegramAiKeyRemoved"));
                        });
                    }));
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private static String versionString() {
        // versione del prodotto; fra parentesi la base upstream da cui deriva questa build
        String base = BuildVars.BUILD_VERSION_STRING;
        try {
            final Context ctx = ApplicationLoader.applicationContext;
            final PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            base = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignore) {
        }
        final String version = org.telegram.messenger.BuildConfig.MORSEGRAM_VERSION + " \u00b7 Telegram " + base;
        return LocaleController.formatString("MorsegramVersion", 0, version);
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            return position == airplaneRow || position == addTileRow || position == hiddenChatsRow || position == archiveHiddenRow || position == lockVoiceModeRow || position == pinRow
                    || position == biometricRow || position == autoLockRow || position == lockNowRow || position == removePinRow
                    || position == aiEnabledRow || position == aiLocalRow || position == aiPrimaryRow || position == aiFallbackRow || position == aiGroqKeyRow || position == aiGeminiKeyRow
                    || position == aiReportsRow
                    // le righe con interruttore degli sblocchi: RecyclerListView usa questo metodo per chiamare
                    // setEnabled sulla cella, e una cella non abilitata non riceve il tocco. La riga degli slot
                    // account resta FUORI di proposito: non ha interruttore, quindi non deve nemmeno illuminarsi
                    || unlockId(position) != -1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case TYPE_SETTINGS:
                default:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    final HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == connectionHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsConnection"));
                    } else if (position == chatHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsChat"));
                    } else if (position == privacyHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsPrivacy"));
                    } else if (position == infoHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsInfo"));
                    } else if (position == voiceHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsVoice"));
                    } else if (position == aiHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsAi"));
                    } else if (position == updateHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramUpdates"));
                    } else if (it.iotatec.morsegram.unlock.LocalUnlocks.enabled() && position == unlocksHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsUnlocks"));
                    } else if (foldRow(position) != null) {
                        cell.setText(foldRow(position).title());
                    }
                    break;
                }
                case TYPE_CHECK: {
                    final TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == airplaneRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramAirplaneMode"), AirplaneMode.isOn(), addTileRow != -1);
                    } else if (position == archiveHiddenRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramArchiveHidden"), SharedConfig.archiveHidden, true);
                    } else if (position == lockVoiceModeRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramLockVoiceMode"), it.iotatec.morsegram.voice.VoiceSettings.recordModeLocked(), false);
                    } else if (position == biometricRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramUseBiometric"), HiddenLock.isBiometricEnabled(), true);
                    } else if (position == aiEnabledRow) {
                        // segue sempre una riga normale quando c'e' (Modello sul telefono, o le righe dei
                        // fornitori con l'assistente acceso): senza aiLocalRow qui mancava la linea con
                        // l'assistente spento, dove la riga del modello c'e' comunque
                        cell.setTextAndCheck(LocaleController.getString("MorsegramAiEnabled"), it.iotatec.morsegram.ai.AiSettings.enabled(), aiLocalRow != -1 || aiPrimaryRow != -1);
                    } else if (position == updateAutoRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramUpdateAuto"), it.iotatec.morsegram.update.Updater.autoCheckEnabled(), true);
                    } else if (position == voiceAllowSystemRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramVoiceAllowSystem"), it.iotatec.morsegram.voice.VoiceSettings.allowSystem(), true);
                    } else if (it.iotatec.morsegram.unlock.LocalUnlocks.enabled() && position == unlocksAccountsRow) {
                        // niente interruttore (setTextAndValue nasconde la casella): il numero di slot si decide una
                        // volta all'avvio, quindi questa voce si puo' solo leggere. Il numero viene da UserConfig,
                        // non da una stringa: resta vero anche se upstream cambia gli slot
                        cell.setTextAndValue(LocaleController.getString("MorsegramUnlockAccounts"),
                                LocaleController.formatString("MorsegramUnlockAccountsInfo", 0, org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT), true, true);
                    } else if (it.iotatec.morsegram.unlock.LocalUnlocks.enabled() && unlockId(position) != -1) {
                        final int id = unlockId(position);
                        cell.setTextAndValueAndCheck(LocaleController.getString(unlockName(id)), LocaleController.getString(unlockName(id) + "Info"),
                                it.iotatec.morsegram.unlock.LocalUnlocks.on(id), true, id < it.iotatec.morsegram.unlock.LocalUnlocks.IDS - 1);
                    } else if (foldRow(position) != null) {
                        final it.iotatec.morsegram.fold.FoldSettings.Row next = foldRow(position + 1);
                        foldRow(position).bind(cell, next != null && next.type == it.iotatec.morsegram.fold.FoldSettings.ROW_CHECK);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    // cella riciclata: il rosso di 'Rimuovi PIN' va azzerato per le altre righe
                    cell.setTextColor(Theme.getColor(position == removePinRow ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
                    if (position == addTileRow) {
                        cell.setText(LocaleController.getString("MorsegramAddTile"), false);
                    } else if (position == localPinsRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramLocalPins"), String.valueOf(localPinsCount()), true);
                    } else if (position == hiddenChatsRow) {
                        // Tre casi: senza PIN si dice che non sono protette; con PIN e sezione bloccata NON si
                        // mostra il conteggio (rivelerebbe quante chat nascoste esistono a chi ha il telefono
                        // sbloccato ma non il PIN); solo a sezione sbloccata si mostra il numero.
                        final String hiddenValue = HiddenLock.isUnprotected(currentAccount)
                                ? LocaleController.getString("MorsegramHiddenUnprotected")
                                : HiddenLock.isLocked() ? "" : String.valueOf(hiddenChatsCount());
                        cell.setTextAndValue(LocaleController.getString("MorsegramHiddenChats"), hiddenValue, true);
                    } else if (position == pinRow) {
                        final String pinStatus = HiddenLock.hasPin() ? "MorsegramPinStatusSet" : "MorsegramPinStatusNotSet";
                        cell.setTextAndValue(LocaleController.getString("MorsegramPin"), LocaleController.getString(pinStatus), true);
                    } else if (position == autoLockRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramAutoLock"), HiddenLock.timeoutLabel(HiddenLock.timeoutSeconds()), lockNowRow != -1 || removePinRow != -1);
                    } else if (position == lockNowRow) {
                        cell.setText(LocaleController.getString("MorsegramLockNow"), removePinRow != -1);
                    } else if (position == removePinRow) {
                        cell.setText(LocaleController.getString("MorsegramRemovePin"), false);
                    } else if (position == versionRow) {
                        cell.setTextAndValue("Morsegram", versionString(), false);
                    } else if (position == voiceModeRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramVoiceMode"), it.iotatec.morsegram.voice.VoiceSettings.modeLabel(it.iotatec.morsegram.voice.VoiceSettings.mode()), true);
                    } else if (position == voiceLangRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramVoiceLanguage"), it.iotatec.morsegram.voice.VoiceSettings.languageLabel(), true);
                    } else if (position == aiLocalRow) {
                        // dopo questa riga c'e' sempre una fascia grigia (aiActiveInfoRow con l'assistente
                        // acceso, aiInfoRow con l'assistente spento): nessuna linea, come per archiveHiddenRow
                        cell.setTextAndValue(LocaleController.getString("MorsegramAiLocalModel"), it.iotatec.morsegram.ai.AiSettings.localStatusText(), false);
                    } else if (position == aiPrimaryRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramAiPrimary"), it.iotatec.morsegram.ai.AiProvider.labelOf(it.iotatec.morsegram.ai.AiSettings.primary()), true);
                    } else if (position == aiFallbackRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramAiFallback"), it.iotatec.morsegram.ai.AiSettings.fallbackLabels()[it.iotatec.morsegram.ai.AiSettings.fallbackIndex()], true);
                    } else if (position == aiGroqKeyRow) {
                        // valore: solo SE la chiave c'e', mai la chiave
                        cell.setTextAndValue(LocaleController.formatString("MorsegramAiKeyOf", 0, it.iotatec.morsegram.ai.AiProvider.labelOf(it.iotatec.morsegram.ai.AiProvider.GROQ)), it.iotatec.morsegram.ai.AiSettings.keyStatus(it.iotatec.morsegram.ai.AiProvider.GROQ), true);
                    } else if (position == aiGeminiKeyRow) {
                        cell.setTextAndValue(LocaleController.formatString("MorsegramAiKeyOf", 0, it.iotatec.morsegram.ai.AiProvider.labelOf(it.iotatec.morsegram.ai.AiProvider.GEMINI)), it.iotatec.morsegram.ai.AiSettings.keyStatus(it.iotatec.morsegram.ai.AiProvider.GEMINI), true);
                    } else if (position == aiActiveChatsRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramAiActiveChats"), String.valueOf(it.iotatec.morsegram.ai.AiGate.activeCount(currentAccount)), true);
                    } else if (position == aiReportsRow) {
                        // obbligo Google Play: da qui si rileggono e si cancellano le segnalazioni fatte
                        cell.setTextAndValue(LocaleController.getString("MorsegramAiReports"), aiReportsCount < 0 ? "" : String.valueOf(aiReportsCount), false);
                    } else if (position == updateStatusRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramUpdateStatus"), updateStatusText(), true);
                    } else if (position == updateSourceRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramUpdateSource"), it.iotatec.morsegram.update.Updater.source(), false);
                    } else if (position == voiceStatusRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramVoiceStatus"), it.iotatec.morsegram.voice.VoiceSettings.probeStatus(), false);
                    }
                    break;
                }
                case TYPE_INFO: {
                    final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    final boolean last = position == licenseInfoRow;
                    cell.setBackground(Theme.getThemedDrawableByKey(mContext, last ? R.drawable.greydivider_bottom : R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    if (position == connectionInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramAirplaneInfo"));
                    } else if (position == chatInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramLocalPinsInfo") + " " + LocaleController.getString("MorsegramArchiveHiddenInfo") + " " + LocaleController.getString("MorsegramLockVoiceModeInfo"));
                    } else if (position == privacyInfoRow) {
                        // l'avviso per esteso sta qui: nel valore della riga ci stanno ~14 caratteri, una riga ellissizzata
                        cell.setText(LocaleController.getString("MorsegramHiddenChatsInfo") + " " + LocaleController.getString("MorsegramHiddenPrivacyInfo")
                                + (HiddenLock.isUnprotected(currentAccount) ? " " + LocaleController.getString("MorsegramHiddenSetPinInfo") : ""));
                    } else if (position == licenseInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramUnofficialClient"));
                    } else if (position == aiActiveInfoRow) {
                        // chi lavora adesso e perche': la riga che dice, sul modello locale, che non esce nulla
                        cell.setText(LocaleController.formatString("MorsegramAiActiveNow", 0, it.iotatec.morsegram.ai.AiSettings.activeProviderLabel())
                                + " " + it.iotatec.morsegram.ai.AiSettings.activeProviderExplanation());
                    } else if (position == aiInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramAiInfo"));
                    } else if (position == updateInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramUpdateInfo"));
                    } else if (it.iotatec.morsegram.unlock.LocalUnlocks.enabled() && position == unlocksInfoRow) {
                        // i limiti che RESTANO su ogni singola voce stanno nella riga della voce; qui sotto va quello
                        // che vale per tutta la sezione: nessun abbonamento simulato, tutto cio' che decidono i
                        // server di Telegram resta a pagamento, e questa sezione non esiste nella versione dello Store
                        cell.setText(LocaleController.getString("MorsegramUnlocksInfo"));
                    } else if (position == voiceInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramVoiceInfo") + " " + LocaleController.getString("MorsegramVoiceAllowSystemInfo"));
                    } else if (foldRow(position) != null) {
                        cell.setText(foldRow(position).title());
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            final it.iotatec.morsegram.fold.FoldSettings.Row fold = foldRow(position);
            if (fold != null) {
                return fold.type == it.iotatec.morsegram.fold.FoldSettings.ROW_HEADER ? TYPE_HEADER : fold.type == it.iotatec.morsegram.fold.FoldSettings.ROW_CHECK ? TYPE_CHECK : TYPE_INFO;
            }
            if (position == connectionHeaderRow || position == chatHeaderRow || position == privacyHeaderRow || position == infoHeaderRow || position == voiceHeaderRow || position == updateHeaderRow || position == aiHeaderRow || position == unlocksHeaderRow) {
                return TYPE_HEADER;
            } else if (position == airplaneRow || position == archiveHiddenRow || position == lockVoiceModeRow || position == biometricRow || position == voiceAllowSystemRow || position == updateAutoRow || position == aiEnabledRow
                    || position == unlocksAccountsRow || unlockId(position) != -1) {
                // le righe degli sblocchi sono TextCheckCell anche quella degli slot account, che pero' si lega con
                // setTextAndValue: stessa vista, casella nascosta, riga di spiegazione sotto il titolo
                return TYPE_CHECK;
            } else if (position == connectionInfoRow || position == chatInfoRow || position == privacyInfoRow || position == licenseInfoRow || position == voiceInfoRow || position == updateInfoRow || position == aiInfoRow || position == aiActiveInfoRow || position == unlocksInfoRow) {
                return TYPE_INFO;
            }
            return TYPE_SETTINGS;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));
        return themeDescriptions;
    }
}
