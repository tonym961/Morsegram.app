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
import org.telegram.messenger.R;
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
 * protetta), Privacy (PIN Morsegram imposta/cambia/rimuovi, sblocco con impronta o volto, blocco automatico, blocca
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
    private int chatInfoRow;
    private java.util.List<it.iotatec.morsegram.fold.FoldSettings.Row> foldRows = java.util.Collections.emptyList();
    private int foldFirstRow = -1;
    private int voiceHeaderRow;
    private int voiceModeRow;
    private int voiceLangRow;
    private int voiceAllowSystemRow;
    private int voiceStatusRow;
    private int voiceInfoRow;
    private int updateHeaderRow;
    private int updateStatusRow;
    private int updateAutoRow;
    private int updateSourceRow;
    private int updateInfoRow;
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
        final boolean upd = it.iotatec.morsegram.update.Updater.isSupported();
        updateHeaderRow = upd ? rowCount++ : -1;
        updateStatusRow = upd ? rowCount++ : -1;
        updateAutoRow = upd ? rowCount++ : -1;
        updateSourceRow = upd ? rowCount++ : -1;
        updateInfoRow = upd ? rowCount++ : -1;
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
    }

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

    /** Rimuove il PIN dopo lo sblocco e una conferma: le chat restano nascoste dalle liste ma non più protette. */
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
            builder.setMessage(LocaleController.getString("MorsegramRemovePinConfirm"));
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
            return position == airplaneRow || position == addTileRow || position == hiddenChatsRow || position == pinRow
                    || position == biometricRow || position == autoLockRow || position == lockNowRow || position == removePinRow;
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
                    } else if (position == updateHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramUpdates"));
                    } else if (foldRow(position) != null) {
                        cell.setText(foldRow(position).title());
                    }
                    break;
                }
                case TYPE_CHECK: {
                    final TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == airplaneRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramAirplaneMode"), AirplaneMode.isOn(), addTileRow != -1);
                    } else if (position == biometricRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramUseBiometric"), HiddenLock.isBiometricEnabled(), true);
                    } else if (position == updateAutoRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramUpdateAuto"), it.iotatec.morsegram.update.Updater.autoCheckEnabled(), true);
                    } else if (position == voiceAllowSystemRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramVoiceAllowSystem"), it.iotatec.morsegram.voice.VoiceSettings.allowSystem(), true);
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
                        cell.setTextAndValue(LocaleController.getString("MorsegramHiddenChats"), String.valueOf(hiddenChatsCount()), false);
                    } else if (position == pinRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramPin"), LocaleController.getString(HiddenLock.hasPin() ? "MorsegramPinStatusSet" : "MorsegramPinStatusNotSet"), true);
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
                        cell.setText(LocaleController.getString("MorsegramLocalPinsInfo"));
                    } else if (position == privacyInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramHiddenChatsInfo") + " " + LocaleController.getString("MorsegramHiddenPrivacyInfo"));
                    } else if (position == licenseInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramUnofficialClient"));
                    } else if (position == updateInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramUpdateInfo"));
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
            if (position == connectionHeaderRow || position == chatHeaderRow || position == privacyHeaderRow || position == infoHeaderRow || position == voiceHeaderRow || position == updateHeaderRow) {
                return TYPE_HEADER;
            } else if (position == airplaneRow || position == biometricRow || position == voiceAllowSystemRow || position == updateAutoRow) {
                return TYPE_CHECK;
            } else if (position == connectionInfoRow || position == chatInfoRow || position == privacyInfoRow || position == licenseInfoRow || position == voiceInfoRow || position == updateInfoRow) {
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
