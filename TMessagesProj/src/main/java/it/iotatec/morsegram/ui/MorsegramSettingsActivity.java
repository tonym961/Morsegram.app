/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

import it.iotatec.morsegram.AirplaneMode;
import it.iotatec.morsegram.LocalPins;

/**
 * Pagina 'Morsegram' (voce nel menu '⋯' della lista chat via ApplicationLoaderImpl.addItemOptions, 0 righe upstream).
 * Sezioni: Connessione (modalità aereo, tile QS), Chat (conteggio pin locali, LocalPins), Info (versione, client non
 * ufficiale che usa le API di Telegram — ToS 2.2 —, licenza). Stile DataSettingsActivity: RecyclerListView + celle upstream.
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
    private int chatInfoRow;
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
        chatInfoRow = rowCount++;
        infoHeaderRow = rowCount++;
        versionRow = rowCount++;
        licenseInfoRow = rowCount++;
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
            }
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // lo stato può essere cambiato dalla tile/notifica mentre la pagina era coperta
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private int localPinsCount() {
        return LocalPins.count(currentAccount);
    }

    private static String versionString() {
        String version = BuildVars.BUILD_VERSION_STRING;
        try {
            final Context ctx = ApplicationLoader.applicationContext;
            final PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            version = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignore) {
        }
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
            return position == airplaneRow || position == addTileRow;
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
                    } else if (position == infoHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramSettingsInfo"));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    final TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == airplaneRow) {
                        cell.setTextAndCheck(LocaleController.getString("MorsegramAirplaneMode"), AirplaneMode.isOn(), addTileRow != -1);
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == addTileRow) {
                        cell.setText(LocaleController.getString("MorsegramAddTile"), false);
                    } else if (position == localPinsRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramLocalPins"), String.valueOf(localPinsCount()), false);
                    } else if (position == versionRow) {
                        cell.setTextAndValue("Morsegram", versionString(), false);
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
                    } else if (position == licenseInfoRow) {
                        cell.setText(LocaleController.getString("MorsegramUnofficialClient"));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == connectionHeaderRow || position == chatHeaderRow || position == infoHeaderRow) {
                return TYPE_HEADER;
            } else if (position == airplaneRow) {
                return TYPE_CHECK;
            } else if (position == connectionInfoRow || position == chatInfoRow || position == licenseInfoRow) {
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
