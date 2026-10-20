/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.car.app.model.CarIcon;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Avatar (foto piccola, già in cache su disco) -> {@link CarIcon} tondo, con cache LRU.
 *
 * <p>Il watchdog dell'host uccide l'app se {@code onGetTemplate()} o una callback impiegano troppo, quindi
 * {@link #get} è sempre O(1): hit -> icona, miss -> null e decodifica su {@link Utilities#globalQueue}; a
 * decodifica finita chiama {@code onReady} sul thread UI, che tipicamente è {@code Screen::invalidate}.
 * Niente download: se la foto non è sul disco la riga resta senza immagine (l'host mette il placeholder).</p>
 */
final class CarAvatars {

    /**
     * Raffica di decodifiche -> una sola invalidate: le righe di una schermata finiscono insieme e ogni
     * invalidate è una IPC verso l'host. Richiede che il chiamante passi <b>sempre la stessa istanza</b> di
     * {@code onReady} (un campo, non {@code this::invalidate} creato sul posto), altrimenti non si coalizza.
     */
    private static final long READY_DEBOUNCE_MS = 150L;

    /** ~6 righe visibili per schermata, più le pagine già viste e gli account: 64 icone bastano. */
    private static final int MAX_CACHE = 64;
    /** l'host scala l'icona a dimensione riga: più di così è banda IPC sprecata (CarIcon viaggia via Binder). */
    private static final int TARGET_PX = 128;

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, CarIcon> cache = new LinkedHashMap<String, CarIcon>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CarIcon> eldest) {
            return size() > MAX_CACHE;
        }
    };
    /** chiavi senza file su disco: non ritentare a ogni invalidate (una invalidate = una passata di righe). */
    private static final HashSet<String> missing = new HashSet<>();
    private static final HashSet<String> pending = new HashSet<>();

    private CarAvatars() {
    }

    /** Chiamare dal thread UI quando si cambia account o si vuole ritentare le foto non ancora scaricate. */
    static void clear() {
        synchronized (lock) {
            cache.clear();
            missing.clear();
            pending.clear();
        }
    }

    @Nullable
    static CarIcon of(int account, @Nullable TLRPC.User user, @Nullable TLRPC.Chat chat, @Nullable Runnable onReady) {
        if (user != null && user.photo != null) {
            return get(account, user.photo.photo_small, user.photo.photo_id, onReady);
        }
        if (chat != null && chat.photo != null) {
            return get(account, chat.photo.photo_small, chat.photo.photo_id, onReady);
        }
        return null;
    }

    /**
     * @param photoId identità della foto: cambia quando l'utente cambia avatar, così la cache si invalida da sé
     * @param onReady eseguito sul thread UI quando l'icona entra in cache (mai subito, mai in caso di assenza)
     */
    @Nullable
    static CarIcon get(int account, @Nullable TLRPC.FileLocation loc, long photoId, @Nullable Runnable onReady) {
        if (loc == null || loc instanceof TLRPC.TL_fileLocationUnavailable) {
            return null;
        }
        final String key = account + "/" + photoId;
        synchronized (lock) {
            CarIcon hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
            if (missing.contains(key) || !pending.add(key)) {
                return null;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            CarIcon icon = decode(account, loc);
            synchronized (lock) {
                pending.remove(key);
                if (icon != null) {
                    cache.put(key, icon);
                } else {
                    missing.add(key);
                }
            }
            if (icon != null && onReady != null) {
                AndroidUtilities.cancelRunOnUIThread(onReady);
                AndroidUtilities.runOnUIThread(onReady, READY_DEBOUNCE_MS);
            }
        });
        return null;
    }

    /**
     * La decodifica vera (file in cache -> bitmap tonda) sta in {@code MorsegramAutoCommon}, nel modulo libreria,
     * perché la usa anche l'albero media di produzione: qui sopra resta solo quello che è specifico dell'auto
     * templata, cioè la cache LRU e l'incarto in {@link CarIcon}.
     */
    @Nullable
    private static CarIcon decode(int account, TLRPC.FileLocation loc) {
        try {
            Bitmap bitmap = MorsegramAutoCommon.avatar(account, loc, TARGET_PX);
            if (bitmap == null) {
                return null;
            }
            return new CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build();
        } catch (Throwable t) {
            return null;
        }
    }
}
