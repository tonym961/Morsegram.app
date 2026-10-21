/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Elenco delle segnalazioni fatte sui risultati dell'assistente, con la possibilità di cancellarle.
 *
 * <p>Si apre dalla riga 'Segnalazioni' della sezione Assistente delle impostazioni. Una riga per segnalazione
 * (data e fornitore come titolo, motivo come valore); il tocco apre la segnalazione intera, da cui si può
 * rileggerla e cancellarla. In barra c'è 'Cancella tutte'. Da qui non parte niente: le segnalazioni restano
 * sul telefono, e nell'app non esiste nessun indirizzo a cui mandarle (vedi {@link AiReports}).</p>
 *
 * <p>La lettura e la scrittura del file stanno su un thread di fondo ({@link AiReports} lo richiede): questa
 * pagina non apre file sul thread principale, nemmeno per contare le righe.</p>
 */
public class AiReportsActivity extends BaseFragment {

    private static final int MENU_CLEAR = 1;

    private static final int TYPE_REPORT = 0;
    private static final int TYPE_INFO = 1;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private TextInfoPrivacyCell emptyCell;

    private List<AiReports.Report> reports = Collections.emptyList();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString("MorsegramAiReports"));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    askClearAll();
                }
            }
        });
        actionBar.createMenu().addItem(MENU_CLEAR, R.drawable.msg_clear)
                .setContentDescription(LocaleController.getString("MorsegramAiReportsClear"));

        listAdapter = new ListAdapter(context);

        final FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < reports.size()) {
                showReport(reports.get(position));
            }
        });

        emptyCell = new TextInfoPrivacyCell(context);
        emptyCell.setText(LocaleController.getString("MorsegramAiReportsEmpty"));
        emptyCell.setVisibility(View.GONE);
        frame.addView(emptyCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        reload();
        return fragmentView;
    }

    /** Rilegge il file su un thread di fondo e ridisegna sulla UI. */
    private void reload() {
        Utilities.globalQueue.postRunnable(() -> {
            final List<AiReports.Report> loaded = AiReports.all();
            AndroidUtilities.runOnUIThread(() -> {
                reports = loaded == null ? new ArrayList<>() : loaded;
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
                if (emptyCell != null) {
                    emptyCell.setVisibility(reports.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    /** Una segnalazione per intero: i campi registrati, poi il testo segnalato. */
    private void showReport(AiReports.Report report) {
        final Activity activity = getParentActivity();
        if (activity == null || report == null) {
            return;
        }
        final StringBuilder head = new StringBuilder();
        head.append(report.when()).append('\n');
        head.append(report.provider);
        if (report.model.length() > 0) {
            head.append(" · ").append(report.model);
        }
        final String task = report.taskLabel();
        if (task.length() > 0) {
            head.append(" · ").append(task);
        }
        head.append('\n').append(report.reasonLabel());
        if (report.comment.length() > 0) {
            head.append("\n\n").append(report.comment);
        }

        final android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);

        final TextView meta = new TextView(activity);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        meta.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        meta.setText(head.toString());
        box.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final TextView body = new TextView(activity);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        body.setTextIsSelectable(true);
        body.setText(report.text);
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(body, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        box.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiReport"));
        builder.setView(box, Math.min(AndroidUtilities.dp(420), (int) (AndroidUtilities.displaySize.y * 0.5f)));
        builder.setNeutralButton(LocaleController.getString(R.string.Delete), (d, which) -> delete(report.id));
        builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        final View neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral instanceof TextView) {
            ((TextView) neutral).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void delete(long id) {
        Utilities.globalQueue.postRunnable(() -> {
            AiReports.remove(id);
            AndroidUtilities.runOnUIThread(() -> {
                reload();
                bulletin(LocaleController.getString("MorsegramAiReportDeleted"));
            });
        });
    }

    private void askClearAll() {
        final Activity activity = getParentActivity();
        if (activity == null || reports.isEmpty()) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiReportsClear"));
        builder.setMessage(LocaleController.getString("MorsegramAiReportsClearConfirm"));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiReportsClear"), (d, which) ->
                Utilities.globalQueue.postRunnable(() -> {
                    AiReports.clear();
                    AndroidUtilities.runOnUIThread(() -> {
                        reload();
                        bulletin(LocaleController.getString("MorsegramAiReportsCleared"));
                    });
                }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        final View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive instanceof TextView) {
            ((TextView) positive).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void bulletin(String text) {
        try {
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return reports.isEmpty() ? 0 : reports.size() + 1;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_REPORT;
        }

        @Override
        public int getItemViewType(int position) {
            return position < reports.size() ? TYPE_REPORT : TYPE_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_INFO) {
                view = new TextInfoPrivacyCell(mContext);
            } else {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == TYPE_INFO) {
                final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setBackground(Theme.getThemedDrawableByKey(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                cell.setText(LocaleController.getString("MorsegramAiReportsInfo"));
                return;
            }
            final AiReports.Report report = reports.get(position);
            final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
            final String title = report.when() + " · " + report.provider;
            cell.setTextAndValue(title, report.reasonLabel(), position < reports.size() - 1);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        final ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        return descriptions;
    }
}
