/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.auto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;

import java.io.File;

import it.iotatec.morsegram.MorsegramDialogs;

/**
 * Quel poco che i <b>due</b> percorsi Android Auto condividono: il filtro delle chat e la decodifica degli avatar.
 *
 * <p>Vive nel modulo libreria perché deve essere raggiungibile sia dal percorso di <b>produzione</b>
 * ({@link MorsegramAutoBrowser}, agganciato alla {@code TelegramMediaSession} upstream) sia dal modulo
 * <b>labs</b> ({@code TMessagesProj_CarLabs}, che dipende da {@code :TMessagesProj}). Il contrario non è
 * possibile: la produzione non può vedere il codice labs, che in produzione non è nemmeno compilato.</p>
 *
 * <p><b>Un solo punto</b> decide quali chat possono comparire sull'head unit ({@link #allowed}); tutte le
 * schermate auto e tutti i media id passano da qui. Il controllo va rifatto su ogni id che arriva dall'host:
 * l'host lo ha ricevuto da noi, ma può ripresentarlo quando vuole (cronologia, "riprendi", scorciatoie) e
 * nel frattempo la chat può essere stata nascosta.</p>
 */
public final class MorsegramAutoCommon {

    private MorsegramAutoCommon() {
    }

    /**
     * L'unico filtro delle chat per l'head unit: niente id vuoti, niente chat segrete, niente chat nascoste.
     *
     * <p>{@code MorsegramDialogs.isHidden} si auto-carica dalle SharedPreferences e non prende lock, quindi è
     * chiamabile da qualunque thread (storageQueue, globalQueue, thread UI).</p>
     */
    public static boolean allowed(int account, long dialogId) {
        return dialogId != 0
                && !DialogObject.isEncryptedDialog(dialogId)
                && !MorsegramDialogs.isHidden(account, dialogId);
    }

    /**
     * Avatar tondo dal file <b>già in cache su disco</b>; qui non si scarica mai niente (una riga di lista non
     * vale una richiesta di rete mentre si guida). Da chiamare fuori dal thread UI.
     *
     * @param targetPx lato desiderato: serve solo a scegliere {@code inSampleSize}, il ritaglio resta alla
     *                 risoluzione decodificata e l'host scala l'icona alla dimensione della riga
     * @return bitmap tonda, oppure {@code null} se la foto non è su disco o non si decodifica
     */
    public static Bitmap avatar(int account, TLRPC.FileLocation location, int targetPx) {
        try {
            if (location == null || location instanceof TLRPC.TL_fileLocationUnavailable) {
                return null;
            }
            // useFileDatabaseQueue=false: siamo su una coda di lavoro, niente attese incrociate
            final File file = FileLoader.getInstance(account).getPathToAttach(location, true);
            if (file == null || !file.exists() || file.length() == 0) {
                return null;
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            final BitmapFactory.Options options = new BitmapFactory.Options();
            if (targetPx > 0 && bounds.outWidth > 0 && bounds.outHeight > 0) {
                options.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / targetPx);
            }
            final Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (source == null) {
                return null;
            }
            return round(source);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Paint e RectF locali: questo metodo gira su più code (storageQueue, globalQueue) in parallelo. */
    private static Bitmap round(Bitmap source) {
        try {
            final Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.TRANSPARENT);
            final Canvas canvas = new Canvas(result);
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(0, 0, source.getWidth(), source.getHeight()),
                    source.getWidth(), source.getHeight(), paint);
            return result;
        } catch (Throwable e) {
            return source;               // meglio un avatar quadrato che nessun avatar
        }
    }

    /**
     * Durata corta da head unit: {@code m:ss}, oppure {@code h:mm:ss} oltre l'ora. Nessun {@code Locale}: sono
     * cifre e due punti, e la riga della lista ha pochissimo spazio.
     */
    public static String clock(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        final int hours = seconds / 3600;
        final int minutes = seconds / 60 % 60;
        final int secs = seconds % 60;
        final StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(':');
            if (minutes < 10) {
                sb.append('0');
            }
        }
        sb.append(minutes).append(':');
        if (secs < 10) {
            sb.append('0');
        }
        return sb.append(secs).toString();
    }

    /**
     * I vocali di una chat sono cambiati (tipico: dall'auto se n'è appena inviato uno).
     *
     * <p>L'albero media di produzione tiene l'indice delle chat con vocali per 60 s: senza questo, una chat
     * appena toccata comparirebbe nella lista dell'head unit solo allo scadere del TTL.</p>
     */
    public static void voicesChanged() {
        MorsegramAutoBrowser.invalidate();
    }
}
