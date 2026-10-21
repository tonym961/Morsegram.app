/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

/**
 * PERSONALIZZAZIONE di un widget Morsegram: quale chat (solo VOCALE RAPIDO e CHAT SINGOLA), tema chiaro/scuro/
 * automatico, angoli, trasparenza dello sfondo. Le dimensioni non sono una riga di questa pagina: sono
 * ridimensionabili direttamente sulla schermata iniziale ({@code resizeMode="horizontal|vertical"} con
 * {@code targetCellWidth/Height} e i limiti {@code minResize*}/{@code maxResize*} nei file
 * {@code res/xml/morsegram_widget_*_info.xml}), e il ridisegno arriva da {@code onAppWidgetOptionsChanged}.
 * <p>
 * Ogni scelta si applica subito (prefs + ridisegno del widget): "Fatto" chiude, il tasto indietro conserva.
 * <p>
 * Il selettore di chat è {@code DialogsActivity} con {@code DIALOGS_TYPE_WIDGET}, che legge
 * {@code MessagesController.dialogsServerOnly}: già privo delle chat nascoste grazie all'hook di 1 riga in
 * {@code sortDialogs}. Residuo noto e non chiuso qui: la RICERCA dentro il selettore sui contatti e sul server.
 */
public class WidgetSettingsFragment extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_INFO = 2;
    private static final int done_item = 1;

    private final int widgetType;
    private final int widgetId;
    private Runnable onDone;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int chatHeaderRow;
    private int chatRow;
    private int chatInfoRow;
    private int lookHeaderRow;
    private int themeRow;
    private int cornersRow;
    private int opacityRow;
    private int lookInfoRow;

    /** 100 = sfondo pieno; 0 = solo testo e icone sopra lo sfondo della schermata iniziale. */
    private static final int[] OPACITY = {100, 90, 75, 60, 40, 20, 0};

    public WidgetSettingsFragment(int widgetType, int widgetId) {
        super(new Bundle());
        this.widgetType = widgetType;
        this.widgetId = widgetId;
    }

    public void setOnDone(Runnable onDone) {
        this.onDone = onDone;
    }

    private boolean needsChat() {
        return widgetType == MorsegramWidgets.TYPE_VOICE || widgetType == MorsegramWidgets.TYPE_CHAT;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        chatHeaderRow = needsChat() ? rowCount++ : -1;
        chatRow = needsChat() ? rowCount++ : -1;
        chatInfoRow = needsChat() ? rowCount++ : -1;
        lookHeaderRow = rowCount++;
        themeRow = rowCount++;
        cornersRow = rowCount++;
        opacityRow = rowCount++;
        lookInfoRow = rowCount++;
    }

    private String title() {
        switch (widgetType) {
            case MorsegramWidgets.TYPE_AIRPLANE:
                return MorsegramWidgets.str("MorsegramAirplaneMode", "Airplane mode");
            case MorsegramWidgets.TYPE_HIDDEN:
                return MorsegramWidgets.str("MorsegramHiddenChats", "Hidden chats");
            case MorsegramWidgets.TYPE_UNREAD:
                return MorsegramWidgets.str("MorsegramWidgetUnread", "Unread");
            case MorsegramWidgets.TYPE_VOICE:
                return MorsegramWidgets.str("MorsegramWidgetVoice", "Quick voice");
            case MorsegramWidgets.TYPE_CHAT:
                return MorsegramWidgets.str("MorsegramWidgetChat", "Chat");
            default:
                return MorsegramWidgets.str("MorsegramWidgetSettings", "Widget");
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(title());
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    done();
                } else if (id == done_item) {
                    done();
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItemWithWidth(done_item, R.drawable.ic_ab_done, org.telegram.messenger.AndroidUtilities.dp(56), LocaleController.getString(R.string.Done));

        listAdapter = new ListAdapter(context);
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position == chatRow) {
                pickChat();
            } else if (position == themeRow) {
                choose("MorsegramWidgetTheme", "Theme", themeLabels(), WidgetConfig.theme(widgetId), which -> {
                    WidgetConfig.setTheme(widgetId, which);
                    apply();
                });
            } else if (position == cornersRow) {
                choose("MorsegramWidgetCorners", "Corners", cornerLabels(), WidgetConfig.corners(widgetId), which -> {
                    WidgetConfig.setCorners(widgetId, which);
                    apply();
                });
            } else if (position == opacityRow) {
                choose("MorsegramWidgetOpacity", "Background", opacityLabels(), opacityIndex(), which -> {
                    WidgetConfig.setOpacity(widgetId, OPACITY[which]);
                    apply();
                });
            }
        });
        return fragmentView;
    }

    private interface Choice {
        void onChosen(int which);
    }

    private void choose(String titleKey, String titleFallback, String[] labels, int selected, Choice choice) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), labels, MorsegramWidgets.str(titleKey, titleFallback), selected, (dialog, which) -> choice.onChosen(which)));
    }

    private String[] themeLabels() {
        return new String[]{
                MorsegramWidgets.str("MorsegramWidgetThemeAuto", "Automatic"),
                MorsegramWidgets.str("MorsegramWidgetThemeLight", "Light"),
                MorsegramWidgets.str("MorsegramWidgetThemeDark", "Dark")
        };
    }

    private String[] cornerLabels() {
        return new String[]{
                MorsegramWidgets.str("MorsegramWidgetCornersRound", "Rounded"),
                MorsegramWidgets.str("MorsegramWidgetCornersSquare", "Square"),
                MorsegramWidgets.str("MorsegramWidgetCornersExtra", "Extra rounded")
        };
    }

    private String[] opacityLabels() {
        final String[] labels = new String[OPACITY.length];
        for (int i = 0; i < OPACITY.length; i++) {
            labels[i] = OPACITY[i] + "%";
        }
        return labels;
    }

    private int opacityIndex() {
        final int value = WidgetConfig.opacity(widgetId);
        for (int i = 0; i < OPACITY.length; i++) {
            if (OPACITY[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private void pickChat() {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_WIDGET);
        args.putBoolean("allowSwitchAccount", true);
        args.putBoolean("checkCanWrite", false);
        final DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((dialogsFragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            WidgetConfig.setAccount(widgetId, dialogsFragment.getCurrentAccount());
            WidgetConfig.setDialogId(widgetId, dids.get(0).dialogId);
            apply();
            dialogsFragment.finishFragment();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            return true;
        });
        presentFragment(fragment);
    }

    /** Scrive e ridisegna subito: il widget sulla schermata iniziale cambia mentre si sceglie. */
    private void apply() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        MorsegramWidgets.refresh(widgetType);
    }

    private void done() {
        apply();
        if (onDone != null) {
            onDone.run();
        } else {
            finishFragment();
        }
    }

    private String chatValue() {
        final long dialogId = WidgetConfig.dialogId(widgetId);
        if (dialogId == 0) {
            return MorsegramWidgets.str("MorsegramWidgetPickChat", "Choose a chat");
        }
        final String name = WidgetDialog.nameOnly(widgetId, WidgetConfig.account(widgetId), dialogId);
        return name == null ? MorsegramWidgets.str("MorsegramWidgetPickChat", "Choose a chat") : name;
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
            return position == chatRow || position == themeRow || position == cornersRow || position == opacityRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
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
                    cell.setText(position == chatHeaderRow
                            ? MorsegramWidgets.str("MorsegramWidgetChatSection", "Chat")
                            : MorsegramWidgets.str("MorsegramWidgetLook", "Appearance"));
                    break;
                }
                case TYPE_SETTINGS: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == chatRow) {
                        cell.setTextAndValue(MorsegramWidgets.str("MorsegramWidgetChatSection", "Chat"), chatValue(), false);
                    } else if (position == themeRow) {
                        cell.setTextAndValue(MorsegramWidgets.str("MorsegramWidgetTheme", "Theme"), themeLabels()[WidgetConfig.theme(widgetId)], true);
                    } else if (position == cornersRow) {
                        cell.setTextAndValue(MorsegramWidgets.str("MorsegramWidgetCorners", "Corners"), cornerLabels()[WidgetConfig.corners(widgetId)], true);
                    } else if (position == opacityRow) {
                        cell.setTextAndValue(MorsegramWidgets.str("MorsegramWidgetOpacity", "Background"), WidgetConfig.opacity(widgetId) + "%", false);
                    }
                    break;
                }
                case TYPE_INFO: {
                    final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    final boolean last = position == lookInfoRow;
                    cell.setBackground(Theme.getThemedDrawableByKey(mContext, last ? R.drawable.greydivider_bottom : R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    cell.setText(position == chatInfoRow
                            ? MorsegramWidgets.str("MorsegramWidgetChatInfo", "Hidden chats are never offered here and never appear on the home screen.")
                            : MorsegramWidgets.str("MorsegramWidgetLookInfo", "Drag the widget edges on the home screen to resize it."));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == chatHeaderRow || position == lookHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == chatInfoRow || position == lookInfoRow) {
                return TYPE_INFO;
            }
            return TYPE_SETTINGS;
        }
    }
}
