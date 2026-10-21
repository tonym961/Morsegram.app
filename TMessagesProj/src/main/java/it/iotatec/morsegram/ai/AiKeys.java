/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Chiavi API dei fornitori AI, cifrate con una chiave AES-256 che vive nell'Android Keystore
 * ({@code AndroidKeyStore}, alias {@link #ALIAS}) e non può essere estratta dal dispositivo.
 *
 * <p>Tre decisioni, tutte deliberate:</p>
 * <ul>
 * <li><b>Mai in chiaro, e mai in MorsegramPrefs.</b> Il testo cifrato sta in un file di preferenze suo,
 *     {@value #FILE}: le preferenze normali dell'assistente (interruttori, scelta del fornitore) restano in
 *     {@code MorsegramPrefs.global()}, i segreti no.</li>
 * <li><b>Fuori dai backup, senza righe upstream.</b> L'app dichiara un {@code android:backupAgent}
 *     ({@code org.telegram.messenger.BackupAgent}, un {@code BackupAgentHelper}) che registra UN SOLO helper, per
 *     i file {@code saved_tokens} e {@code saved_tokens_login}. Con un backup agent dichiarato niente altro viene
 *     salvato: {@value #FILE} è escluso per costruzione, e la chiave AES del Keystore non è comunque esportabile,
 *     quindi anche un backup rubato non contiene nulla di utilizzabile.</li>
 * <li><b>Nessun ripiego in chiaro.</b> Se il Keystore non funziona (dispositivi con implementazioni rotte), il
 *     salvataggio FALLISCE e lo si dice all'utente. Non si scrive la chiave in chiaro per far funzionare la
 *     funzione: una chiave API in chiaro è esattamente il problema che questa classe esiste per evitare.</li>
 * </ul>
 *
 * <p><b>Niente crittografia sul thread principale.</b> {@link #key(int)} decifra e {@link #setKey(int, String)}
 * cifra -- ed e' la piu' costosa delle due, perche' alla PRIMA chiave salvata genera anche la AES-256 nel
 * Keystore: su dispositivi lenti sono centinaia di ms, fino a oltre un secondo, passati dentro keystore2/TEE.
 * {@code key} gira sulla coda di fondo di {@link AiClient}; {@code setKey} e {@link #clear(int)} vanno chiamati
 * dalla coda di fondo da chi li usa (lo fa {@code MorsegramSettingsActivity.onAiKeyClick}).
 * {@link #hasKey(int)} legge solo una preferenza e si può chiamare da qualunque thread.</p>
 */
public final class AiKeys {

    /** File di preferenze dedicato ai soli segreti, separato da quelli di {@code MorsegramPrefs}. */
    public static final String FILE = "morsegram_ai_keys";

    private static final String ALIAS = "morsegram_ai_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private AiKeys() {
    }

    private static SharedPreferences prefs() {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String prefKey(int providerId) {
        return providerId == AiProvider.GEMINI ? "key.gemini" : "key.groq";
    }

    /** Se per quel fornitore c'è una chiave salvata. Non la decifra: nelle impostazioni serve solo questo. */
    public static boolean hasKey(int providerId) {
        final SharedPreferences p = prefs();
        return p != null && !TextUtils.isEmpty(p.getString(prefKey(providerId), null));
    }

    /**
     * Salva la chiave cifrata (stringa vuota o null = la rimuove). DA THREAD DI FONDO: vedi il javadoc di classe.
     *
     * @return false se il Keystore non ha potuto cifrare: in quel caso NIENTE è stato salvato.
     */
    public static boolean setKey(int providerId, String plain) {
        final SharedPreferences p = prefs();
        if (p == null) {
            return false;
        }
        if (plain == null || plain.trim().length() == 0) {
            p.edit().remove(prefKey(providerId)).apply();
            return true;
        }
        final String encrypted = encrypt(plain.trim());
        if (encrypted == null) {
            return false;
        }
        p.edit().putString(prefKey(providerId), encrypted).apply();
        return true;
    }

    public static void clear(int providerId) {
        final SharedPreferences p = prefs();
        if (p != null) {
            p.edit().remove(prefKey(providerId)).apply();
        }
    }

    /**
     * La chiave in chiaro, o null se non c'è o non si può decifrare (Keystore azzerato, dispositivo ripristinato).
     * In quel secondo caso la voce viene rimossa: meglio chiedere di nuovo la chiave che ritentare per sempre.
     * Da chiamare su thread di fondo.
     */
    public static String key(int providerId) {
        final SharedPreferences p = prefs();
        if (p == null) {
            return null;
        }
        final String stored = p.getString(prefKey(providerId), null);
        if (TextUtils.isEmpty(stored)) {
            return null;
        }
        final String plain = decrypt(stored);
        if (plain == null) {
            p.edit().remove(prefKey(providerId)).apply();
        }
        return plain;
    }

    /* ================================================================================ crittografia */

    private static String encrypt(String plain) {
        try {
            final SecretKey secret = secretKey(true);
            if (secret == null) {
                return null;
            }
            final Cipher cipher = Cipher.getInstance(TRANSFORM);
            // setRandomizedEncryptionRequired(true): l'IV lo genera il Keystore, non lo scegliamo noi
            cipher.init(Cipher.ENCRYPT_MODE, secret);
            final byte[] iv = cipher.getIV();
            final byte[] ct = cipher.doFinal(plain.getBytes("UTF-8"));
            if (iv == null || iv.length != IV_BYTES) {
                return null;
            }
            final byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            final String encoded = Base64.encodeToString(out, Base64.NO_WRAP);
            Arrays.fill(out, (byte) 0);
            return encoded;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String decrypt(String stored) {
        try {
            final SecretKey secret = secretKey(false);
            if (secret == null) {
                return null;
            }
            final byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            if (all.length <= IV_BYTES) {
                return null;
            }
            final byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            final byte[] ct = Arrays.copyOfRange(all, IV_BYTES, all.length);
            final Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(GCM_TAG_BITS, iv));
            final byte[] plain = cipher.doFinal(ct);
            final String result = new String(plain, "UTF-8");
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(all, (byte) 0);
            return result;
        } catch (Throwable e) {
            // chiave invalidata o testo cifrato alterato: non è un errore da log rumoroso, ma va saputo in debug
            FileLog.e(e, false);
            return null;
        }
    }

    /** La chiave AES nel Keystore; la crea al primo salvataggio. */
    private static SecretKey secretKey(boolean create) throws Exception {
        final KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        final KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        if (!create) {
            return null;
        }
        final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // nessuna autenticazione utente: l'assistente deve funzionare senza una richiesta di impronta a
                // ogni richiesta, e la protezione che serve qui e' che la chiave non esca dal dispositivo
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
