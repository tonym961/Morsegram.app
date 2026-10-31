package it.iotatec.morsegram.wear;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.wearable.Wearable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.TextHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lato telefono del ponte di accesso dall'orologio Morsegram. Il lato orologio e'
 * {@code it.iotatec.morsegram.wear.auth.WearAuthBridge} (modulo TMessagesProj_Wear) e il protocollo
 * e' rispecchiato byte per byte; il trasporto e' il Wearable Data Layer sul prefisso
 * <b>{@code /mg-wear-auth/}</b>, consegnato da {@link MorsegramWearAuthService}.
 *
 * <p><b>Perche' esiste, invece di usare {@code org.telegram.ui.WearAuthSheet} di upstream.</b>
 * Quello mintava un token web con {@code messages.requestUrlAuth} e lo faceva consumare
 * all'orologio con {@code auth.importWebTokenAuthorization}: chiamata riservata alle app ufficiali,
 * che con il nostro api_id il server liquida con {@code webauth_token_expired} (diagnosi in
 * MORSEGRAM.md, 30/10/2026). Qui i ruoli sono invertiti: il <b>segreto lo tiene l'orologio</b> —
 * e' il token del percorso QR, chiesto con il nostro api_id — e il telefono fa solo
 * {@code auth.acceptLoginToken}, le stesse righe che upstream esegue inquadrando un QR
 * ({@code LaunchActivity:3221-3232}), aperte a tutti i client.
 *
 * <p>Il foglio di upstream e il suo {@code /tg-wear-auth/} restano intatti e inutilizzati: zero
 * righe dentro {@code org/telegram}.
 *
 * <p>Il token QR vive pochi secondi e TDLib lo rinnova da se', quindi non viene chiesto in anticipo:
 * al tocco su "Accedi" il telefono manda {@code /ready} e l'orologio risponde con il link piu'
 * fresco. Tutto cio' che e' segreto viaggia in AES-256-GCM sotto la chiave Diffie-Hellman
 * confermata dalle quattro emoji.
 *
 * <p>Con la verifica in due passaggi attiva, dopo l'accettazione il server chiede la password cloud
 * <b>all'orologio</b> (e' il comportamento del percorso QR, non una nostra scelta): l'orologio ha
 * la sua schermata password e la usa.
 */
public class MorsegramWearAuth {

    public static final String PATH_OFFER = "/mg-wear-auth/offer";
    public static final String PATH_ANSWER = "/mg-wear-auth/answer";
    public static final String PATH_READY = "/mg-wear-auth/ready";
    public static final String PATH_QR = "/mg-wear-auth/qr";
    public static final String PATH_RESULT = "/mg-wear-auth/result";
    public static final String PATH_DECLINED = "/mg-wear-auth/declined";
    public static final String PATH_CANCEL = "/mg-wear-auth/cancel";

    private static final int AES_GCM_IV_LEN = 12;
    private static final int AES_GCM_TAG_LEN = 16;

    /**
     * Primo byte del payload di {@code /result}. Il terzo stato esiste per un caso preciso: se il
     * {@code /qr} arriva dopo che {@link #QR_WAIT_MS} e' scaduto, senza di esso l'orologio resterebbe
     * ad aspettare un esito che non arriva mai e finirebbe per dire "controlla la versione sul
     * telefono", che sarebbe falso. Con {@code GAVE_UP} dice la verita': il telefono ha smesso di
     * aspettare, riprova.
     */
    private static final byte RESULT_REFUSED = 0;
    private static final byte RESULT_OK = 1;
    private static final byte RESULT_GAVE_UP = 2;

    /**
     * Separazione di direzione per AES-GCM. I due messaggi cifrati viaggiano sotto la <b>stessa</b>
     * chiave: senza un AAD diverso per verso, un {@code /qr} rimandato indietro sul percorso
     * {@code /result} si decifrerebbe senza errori e il suo primo byte (il primo byte del token)
     * finirebbe interpretato come stato — 1 darebbe un falso "accettato". Con l'AAD la verifica del
     * tag fallisce e il messaggio viene scartato. Stesse costanti in {@code WearAuthBridge}.
     */
    private static final byte[] AAD_QR = "mg-wear-auth/qr".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AAD_RESULT = "mg-wear-auth/result".getBytes(StandardCharsets.US_ASCII);

    private static final BigInteger DH_P = new BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
        "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
        "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
        "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
        "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
        "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF",
        16);
    private static final BigInteger DH_G = BigInteger.valueOf(2);
    private static final int DH_PUBKEY_LEN = 256;

    private static final int SESSION_ID_LEN = 16;
    private static final int NONCE_LEN = 16;

    /**
     * Quanto si aspetta il {@code /qr} dopo aver mandato {@code /ready}. Deve stare sopra al
     * {@code QR_LINK_TIMEOUT_MS} dell'orologio (20 s), che e' il tempo che lui si da' per avere un
     * link fresco da TDLib: piu' corto, e si annullerebbe un accesso che stava per riuscire.
     */
    private static final long QR_WAIT_MS = 30_000L;

    /**
     * L'offerta arriva quasi sempre ad app chiusa: il servizio porta avanti {@code LaunchActivity},
     * ma il foglio e' un {@code BottomSheet} e senza un'attivita' viva {@code show()} morirebbe con
     * {@code BadTokenException} su un context di applicazione. Quindi si riprova finche' l'attivita'
     * c'e', per al massimo {@code SHOW_RETRY_MS * SHOW_MAX_ATTEMPTS} = 6 s.
     */
    private static final long SHOW_RETRY_MS = 250L;
    private static final int SHOW_MAX_ATTEMPTS = 24;

    private static BottomSheet currentSheet;
    private static AuthSession currentSession;
    private static Runnable qrTimeout;
    private static Runnable showRetry;
    private static int showAttempts;
    /** Bottone "Accedi" del secondo foglio: serve a spegnere il caricamento quando l'esito arriva da fuori. */
    private static ButtonWithCounterView acceptButton;

    private static class AuthSession {
        final byte[] sessionId;
        final byte[] peerPub;
        final String originNodeId;

        byte[] sharedKey;
        List<String> emojis;
        /** Account scelto nel primo foglio: quello che verra' autorizzato sull'orologio. */
        int account = -1;
        /** Vero fra il {@code /ready} spedito e il {@code /qr} ricevuto (o scaduto). */
        boolean waitingQr;

        AuthSession(byte[] sessionId, byte[] peerPub, String originNodeId) {
            this.sessionId = sessionId;
            this.peerPub = peerPub;
            this.originNodeId = originNodeId;
        }

        byte[] acceptAndBuildAnswer() {
            final SecureRandom random = new SecureRandom();
            final BigInteger x = new BigInteger(2048, random);
            final BigInteger gx = DH_G.modPow(x, DH_P);
            if (!isValidPub(gx))
                throw new IllegalStateException("our pubkey invalid (extremely unlikely)");
            final byte[] pkPhone = encode256(gx);

            final BigInteger peer = new BigInteger(1, peerPub);
            if (!isValidPub(peer))
                throw new IllegalArgumentException("peer pubkey out of range");
            final byte[] ss = encode256(peer.modPow(x, DH_P));

            final byte[] nonceP = new byte[NONCE_LEN];
            random.nextBytes(nonceP);

            sharedKey = sha256(ss, sessionId, nonceP);
            emojis = emojify(sha256(ss, peerPub), 4);

            FileLog.d("mg-wear-auth: built answer; session " + hex(sessionId) + " emojis=" + emojis);

            final byte[] answer = new byte[SESSION_ID_LEN + NONCE_LEN + DH_PUBKEY_LEN];
            System.arraycopy(sessionId, 0, answer, 0, SESSION_ID_LEN);
            System.arraycopy(nonceP, 0, answer, SESSION_ID_LEN, NONCE_LEN);
            System.arraycopy(pkPhone, 0, answer, SESSION_ID_LEN + NONCE_LEN, DH_PUBKEY_LEN);
            return answer;
        }

        /** Vero se il messaggio porta il nostro sessionId e la chiave c'e': solo allora e' roba nostra. */
        boolean isOurs(byte[] data) {
            return data != null
                && data.length >= SESSION_ID_LEN + AES_GCM_IV_LEN + AES_GCM_TAG_LEN + 1
                && sharedKey != null
                && MessageDigest.isEqual(sessionId, Arrays.copyOfRange(data, 0, SESSION_ID_LEN));
        }

        /** qr = sessionId(16) || iv(12) || AES-GCM(token grezzo). Null = cifratura non verificata. */
        byte[] openToken(byte[] data) {
            final byte[] iv = Arrays.copyOfRange(data, SESSION_ID_LEN, SESSION_ID_LEN + AES_GCM_IV_LEN);
            final byte[] ct = Arrays.copyOfRange(data, SESSION_ID_LEN + AES_GCM_IV_LEN, data.length);
            try {
                return aeadDecrypt(sharedKey, iv, AAD_QR, ct);
            } catch (Exception e) {
                FileLog.e("mg-wear-auth: token decrypt failed: " + e.getMessage());
                return null;
            }
        }

        /** result = sessionId(16) || iv(12) || AES-GCM(byte stato || messaggio utf8). */
        byte[] sealResult(byte status, String message) throws Exception {
            final byte[] text = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
            final byte[] plain = new byte[1 + text.length];
            plain[0] = status;
            System.arraycopy(text, 0, plain, 1, text.length);

            final byte[] iv = new byte[AES_GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            final byte[] ct = aeadEncrypt(sharedKey, iv, AAD_RESULT, plain);

            final byte[] wire = new byte[SESSION_ID_LEN + AES_GCM_IV_LEN + ct.length];
            System.arraycopy(sessionId, 0, wire, 0, SESSION_ID_LEN);
            System.arraycopy(iv, 0, wire, SESSION_ID_LEN, AES_GCM_IV_LEN);
            System.arraycopy(ct, 0, wire, SESSION_ID_LEN + AES_GCM_IV_LEN, ct.length);
            return wire;
        }
    }

    // ---- ingressi dal Data Layer (sempre sul thread UI) ------------------------------------------

    public static void onOfferReceived(byte[] data, String sourceNodeId) {
        if (data == null || data.length != SESSION_ID_LEN + DH_PUBKEY_LEN) {
            FileLog.d("mg-wear-auth: malformed offer (" + (data == null ? -1 : data.length) + ")");
            return;
        }
        final byte[] sid = Arrays.copyOfRange(data, 0, SESSION_ID_LEN);
        final byte[] pub = Arrays.copyOfRange(data, SESSION_ID_LEN, data.length);
        if (currentSession != null && Arrays.equals(currentSession.sessionId, sid)) {
            FileLog.d("mg-wear-auth: duplicate offer (same sessionId) - ignoring");
            return;
        }
        FileLog.d("mg-wear-auth: new session " + hex(sid) + " from " + sourceNodeId);
        cancelQrTimeout();
        currentSession = new AuthSession(sid, pub, sourceNodeId);
        showAttempts = 0;
        showWhenActivityIsReady();
    }

    /** Vedi {@link #SHOW_RETRY_MS}: aspetta che ci sia un'attivita' su cui poter aprire il foglio. */
    private static void showWhenActivityIsReady() {
        cancelShowRetry();
        if (currentSession == null) return;
        if (LaunchActivity.instance != null) {
            show();
            return;
        }
        if (++showAttempts > SHOW_MAX_ATTEMPTS) {
            FileLog.d("mg-wear-auth: nessuna attivita' dopo "
                + (SHOW_RETRY_MS * SHOW_MAX_ATTEMPTS) + " ms: offerta lasciata cadere");
            final AuthSession session = currentSession;
            currentSession = null;
            sendDeclined(session);
            return;
        }
        showRetry = () -> {
            showRetry = null;
            showWhenActivityIsReady();
        };
        AndroidUtilities.runOnUIThread(showRetry, SHOW_RETRY_MS);
    }

    private static void cancelShowRetry() {
        if (showRetry != null) {
            AndroidUtilities.cancelRunOnUIThread(showRetry);
            showRetry = null;
        }
    }

    public static void onQrReceived(byte[] data, String sourceNodeId) {
        final AuthSession session = currentSession;
        if (session == null || !session.isOurs(data)) {
            // Messaggio di un'altra sessione (o troppo corto): non e' affar nostro.
            FileLog.d("mg-wear-auth: /qr not for this session - ignoring");
            return;
        }
        if (!session.waitingQr) {
            // Token arrivato dopo che l'attesa era scaduta: diciamolo all'orologio, altrimenti
            // resta appeso e poi incolpa la versione dell'app sul telefono.
            FileLog.d("mg-wear-auth: /qr arrived after the wait expired");
            sendResult(session, RESULT_GAVE_UP, "");
            return;
        }
        final byte[] token = session.openToken(data);
        if (token == null || token.length == 0) {
            FileLog.d("mg-wear-auth: /qr with our sessionId but undecryptable");
            failWaiting(getString("MorsegramWearAuthBadData"));
            return;
        }
        cancelQrTimeout();
        session.waitingQr = false;
        FileLog.d("mg-wear-auth: login token from " + sourceNodeId + " (" + token.length + " bytes)");
        acceptLoginToken(session, token);
    }

    /**
     * Il {@code /cancel} porta il sessionId: senza, un cancel in ritardo (p.es. del "Riprova"
     * dell'orologio, spedito mentre la nuova offerta e' gia' arrivata) chiuderebbe la sessione
     * appena aperta invece di quella abbandonata.
     */
    public static void onCancelReceived(byte[] data) {
        final AuthSession session = currentSession;
        if (session == null) return;
        if (data == null || data.length != SESSION_ID_LEN
                || !MessageDigest.isEqual(session.sessionId, data)) {
            FileLog.d("mg-wear-auth: /cancel di un'altra sessione - ignorato");
            return;
        }
        FileLog.d("mg-wear-auth: cancel received; dropping session and dismissing sheet");
        cancelQrTimeout();
        cancelShowRetry();
        currentSession = null;
        cancel();
    }

    // ---- la chiamata che autorizza l'orologio ----------------------------------------------------

    /**
     * {@code auth.acceptLoginToken} con la sessione del telefono: identica alla scansione del QR di
     * upstream. L'esito torna all'orologio su {@code /result} in ogni caso, cosi' lui non resta
     * appeso: se non lo riceve, la sua attesa scade e lo dice.
     */
    private static void acceptLoginToken(AuthSession session, byte[] token) {
        final int account = session.account >= 0 ? session.account : UserConfig.selectedAccount;
        final TLRPC.TL_auth_acceptLoginToken req = new TLRPC.TL_auth_acceptLoginToken();
        req.token = token;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            final boolean ok = response instanceof TLRPC.TL_authorization;
            final String text = error != null && error.text != null ? error.text : "NO_AUTH";
            // L'esito va SEMPRE all'orologio: chiave e nodo sono quelli della sessione catturata,
            // anche se nel frattempo e' cominciata un'altra sessione.
            sendResult(session, ok ? RESULT_OK : RESULT_REFUSED, ok ? "" : text);
            if (ok) {
                FileLog.d("mg-wear-auth: acceptLoginToken ok, account=" + account);
            } else {
                FileLog.e("mg-wear-auth: acceptLoginToken failed: " + text);
            }
            // La richiesta puo' tornare molto dopo (coda di ConnectionsManager senza scadenza): se
            // intanto l'orologio ha ricominciato, foglio e sessione in corso NON sono piu' nostri e
            // toccarli vorrebbe dire chiudere il foglio sotto le dita dell'utente.
            if (currentSession != session) {
                FileLog.d("mg-wear-auth: esito di una sessione sorpassata: niente tocchi alla UI");
                return;
            }
            currentSession = null;
            if (ok) {
                cancel();
            } else {
                showSheetErrorCode(text);
            }
        }));
    }

    /**
     * Dice all'orologio che qui non si va avanti, quando una chiave condivisa non c'e' ancora e
     * quindi non si puo' mandare un {@code /result} autenticato: foglio scartato prima di
     * confermare, nessun account attivo, nessuna finestra su cui aprire il foglio. Senza questo,
     * l'orologio aspetterebbe tre minuti e poi darebbe la colpa alla versione dell'app sul telefono.
     * Il sessionId viaggia in chiaro: non e' un segreto e qui non c'e' niente da proteggere.
     */
    private static void sendDeclined(AuthSession session) {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;
        Wearable.getMessageClient(ctx)
            .sendMessage(session.originNodeId, PATH_DECLINED, session.sessionId)
            .addOnFailureListener(e -> FileLog.e("mg-wear-auth: /declined send failed: " + e.getMessage()));
    }

    private static void sendResult(AuthSession session, byte status, String message) {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || session.sharedKey == null) return;
        final byte[] wire;
        try {
            wire = session.sealResult(status, message);
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        Wearable.getMessageClient(ctx)
            .sendMessage(session.originNodeId, PATH_RESULT, wire)
            .addOnFailureListener(e -> FileLog.e("mg-wear-auth: /result send failed: " + e.getMessage()));
    }

    // ---- attesa del token --------------------------------------------------------------------------

    private static void armQrTimeout() {
        cancelQrTimeout();
        qrTimeout = () -> {
            qrTimeout = null;
            final AuthSession session = currentSession;
            if (session == null || !session.waitingQr) return;
            session.waitingQr = false;
            FileLog.d("mg-wear-auth: watch did not send the login token in time");
            failWaiting(getString("MorsegramWearAuthNoAnswer"));
        };
        AndroidUtilities.runOnUIThread(qrTimeout, QR_WAIT_MS);
    }

    private static void cancelQrTimeout() {
        if (qrTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(qrTimeout);
            qrTimeout = null;
        }
    }

    /**
     * L'attesa del token e' finita male: il foglio resta aperto, con l'errore e il bottone di nuovo
     * attivo. La sessione **non** viene buttata di proposito: se il {@code /qr} arriva in ritardo
     * serve ancora la chiave per rispondere {@link #RESULT_GAVE_UP}.
     */
    private static void failWaiting(String message) {
        final AuthSession session = currentSession;
        if (session != null) session.waitingQr = false;
        showSheetMessage(message);
    }

    /** Frase nostra, mostrata com'e'. */
    private static void showSheetMessage(String message) {
        final BottomSheet sheet = currentSheet;
        if (sheet == null) return;
        if (acceptButton != null) acceptButton.setLoading(false);
        try {
            BulletinFactory.of(sheet.topBulletinContainer, sheet.getResourcesProvider())
                .createErrorBulletin(message).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Codice di errore del server (p.es. {@code AUTH_TOKEN_EXPIRED}): va passato a
     * {@code showForError}, che lo incornicia come fa upstream ("Errore sconosciuto: CODICE").
     * Una frase intera NON va qui, finirebbe dentro quella cornice.
     */
    private static void showSheetErrorCode(String errorCode) {
        final BottomSheet sheet = currentSheet;
        if (sheet == null) return;
        if (acceptButton != null) acceptButton.setLoading(false);
        try {
            BulletinFactory.of(sheet.topBulletinContainer, sheet.getResourcesProvider()).showForError(errorCode);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ---- fogli -------------------------------------------------------------------------------------

    /** Primo foglio: avviso, scelta dell'account, "Avanti" manda l'answer e porta alle emoji. */
    public static void show() {
        // Serve un'attivita' vera: su un context di applicazione il BottomSheet muore con
        // BadTokenException. Chi chiama (showWhenActivityIsReady) riprova.
        final Context context = LaunchActivity.instance;
        if (context == null) return;

        Theme.ResourcesProvider resourcesProvider = null;
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            resourcesProvider = fragment.getResourceProvider();
        }

        cancel();

        final BottomSheet.Builder b = new BottomSheet.Builder(context, false, resourcesProvider);

        final FrameLayout container = new FrameLayout(context);
        b.setCustomView(container);

        int nonTestAccount = UserConfig.selectedAccount;
        final ArrayList<Integer> accountNumbers = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                if (!ConnectionsManager.getInstance(a).isTestBackend()) {
                    nonTestAccount = a;
                }
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            return Long.compare(l1, l2);
        });
        if (accountNumbers.isEmpty()) {
            // Nessun account sul telefono: non c'e' niente da passare all'orologio, e lui lo
            // scoprira' solo allo scadere della sua attesa. Almeno resta scritto nel log.
            FileLog.d("mg-wear-auth: nessun account attivo sul telefono: offerta inutile");
            final AuthSession session = currentSession;
            currentSession = null;
            if (session != null) sendDeclined(session);
            return;
        }

        final FrameLayout accountSelectorLayout = new FrameLayout(context);
        final FrameLayout accountSelectorInnerLayout = new FrameLayout(context);
        accountSelectorInnerLayout.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider)));
        final BackupImageView accountImageView = new BackupImageView(context);
        accountImageView.setRoundRadius(dp(14));
        accountImageView.getImageReceiver().setCrossfadeWithOldImage(true);
        final AvatarDrawable accountAvatarDrawable = new AvatarDrawable();

        final int[] selectedAccount = new int[] { UserConfig.selectedAccount };
        final TLRPC.User self = UserConfig.getInstance(selectedAccount[0]).getCurrentUser();
        accountAvatarDrawable.setInfo(self);
        accountImageView.setForUserOrChat(self, accountAvatarDrawable);

        accountSelectorInnerLayout.addView(accountImageView, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.FILL_VERTICAL));
        final ImageView accountSelectorIconView = new ImageView(context);
        accountSelectorIconView.setScaleType(ImageView.ScaleType.CENTER);
        accountSelectorIconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider), PorterDuff.Mode.SRC_IN));
        accountSelectorIconView.setImageResource(R.drawable.arrows_select);
        accountSelectorInnerLayout.addView(accountSelectorIconView, LayoutHelper.createFrame(18, 18, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        accountSelectorLayout.addView(accountSelectorInnerLayout, LayoutHelper.createFrame(52, 28, Gravity.CENTER));
        accountSelectorLayout.setPadding(dp(8), dp(4), dp(8), 0);
        container.addView(accountSelectorLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, Gravity.LEFT | Gravity.TOP, 6, 4, 6, 0));
        ScaleStateListAnimator.apply(accountSelectorLayout);
        if (accountNumbers.size() <= 1) {
            accountSelectorLayout.setVisibility(View.GONE);
        }

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        container.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        final BackupImageView stickerView = new BackupImageView(context);
        layout.addView(stickerView, LayoutHelper.createLinear(130, 130, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 32, 32, 9.66f));
        MediaDataController.getInstance(nonTestAccount).setPlaceholderImage(stickerView, "Utya3D", "😎", "130_130");

        final TextView titleView = TextHelper.makeTextView(context, 20, Theme.key_dialogTextBlack, true, resourcesProvider);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(getString("MorsegramWearAuthTitle"));
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 24, 32, 9.66f));

        final TextView subtitleView = TextHelper.makeTextView(context, 14, Theme.key_dialogTextBlack, false);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setText(getString("MorsegramWearAuthText"));
        layout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 0, 32, 24));

        final ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString("MorsegramWearAuthNext"));
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL, 12, 12, 12, 8));

        final BottomSheet sheet = b.create();
        sheet.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        sheet.fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));

        accountSelectorLayout.setOnClickListener(v -> {
            ItemOptions i = ItemOptions.makeOptions(sheet.container, sheet.getResourcesProvider(), accountSelectorInnerLayout);
            for (int account : accountNumbers) {
                final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
                if (user == null) continue;
                i.addAccount(account, selectedAccount[0] == account, () -> {
                    selectedAccount[0] = account;
                    accountAvatarDrawable.setInfo(user);
                    accountImageView.setForUserOrChat(user, accountAvatarDrawable);
                });
            }
            i
                .setDrawScrim(false)
                .setOnTopOfScrim()
                .setDimAlpha(0)
                .setGravity(Gravity.LEFT)
                .translate(-dp(8), -dp(8))
                .show();
        });

        button.setOnClickListener(v -> {
            if (button.isLoading()) return;
            final AuthSession session = currentSession;
            if (session == null) return;
            button.setLoading(true);
            final Context ctx = v.getContext().getApplicationContext();
            final byte[] payload;
            try {
                payload = session.acceptAndBuildAnswer();
            } catch (Exception e) {
                FileLog.e(e);
                button.setLoading(false);
                return;
            }
            session.account = selectedAccount[0];
            Wearable.getMessageClient(ctx)
                .sendMessage(session.originNodeId, PATH_ANSWER, payload)
                .addOnSuccessListener(id -> {
                    FileLog.d("mg-wear-auth: /answer delivered to " + session.originNodeId);
                    button.setLoading(false);
                    // La consegna e' asincrona: se intanto e' arrivata un'altra offerta, le emoji
                    // da mostrare sarebbero quelle della sessione sorpassata.
                    if (currentSession != session) {
                        FileLog.d("mg-wear-auth: /answer di una sessione sorpassata: niente emoji");
                        return;
                    }
                    showEmojis(session.emojis);
                })
                .addOnFailureListener(e -> {
                    FileLog.e("mg-wear-auth: /answer send failed: " + e.getMessage());
                    button.setLoading(false);
                    if (currentSession == session) showSheetMessage(getString("MorsegramWearAuthNoAnswer"));
                });
        });

        currentSheet = sheet;
        acceptButton = null;
        sheet.setOnDismissListener(() -> onSheetDismissed(sheet));
        sheet.show();
    }

    /** Secondo foglio: le quattro emoji da confrontare, poi "Accedi" chiede il token all'orologio. */
    public static void showEmojis(List<String> emojis) {
        if (emojis == null || emojis.isEmpty()) return;

        final Context context = LaunchActivity.instance;
        if (context == null) return;

        Theme.ResourcesProvider resourcesProvider = null;
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            resourcesProvider = fragment.getResourceProvider();
        }

        cancel();

        final BottomSheet.Builder b = new BottomSheet.Builder(context, false, resourcesProvider);
        final FrameLayout container = new FrameLayout(context);
        b.setCustomView(container);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        container.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        final TextView titleView = TextHelper.makeTextView(context, 20, Theme.key_dialogTextBlack, true, resourcesProvider);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(getString("MorsegramWearAuthEmojis"));
        layout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 24, 32, 9.66f));

        final LinearLayout emojisLayout = new LinearLayout(context);
        emojisLayout.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < emojis.size(); ++i) {
            final String emoji = emojis.get(i);
            final Drawable emojiDrawable = Emoji.getEmojiBigDrawable(emoji);
            final ImageView imageView = new ImageView(context);
            imageView.setImageDrawable(emojiDrawable);
            NotificationCenter.listenEmojiLoading(imageView);
            imageView.setBackground(Theme.createCircleDrawable(dp(80), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), .15f)));
            emojisLayout.addView(imageView, LayoutHelper.createLinear(80, 80, i == 0 ? 0 : 5, 0, 0, 0));
        }
        layout.addView(emojisLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 12, 32, 12));

        final ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString("MorsegramWearAuthLogIn"));
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL, 12, 12, 12, 8));

        final BottomSheet sheet = b.create();
        sheet.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        sheet.fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));

        currentSheet = sheet;
        acceptButton = button;
        sheet.setOnDismissListener(() -> onSheetDismissed(sheet));
        sheet.show();

        button.setOnClickListener(v -> {
            if (button.isLoading()) return;
            final AuthSession session = currentSession;
            if (session == null || session.sharedKey == null) {
                FileLog.d("mg-wear-auth: login pressed with no session/key");
                return;
            }
            button.setLoading(true);
            session.waitingQr = true;
            armQrTimeout();
            final Context ctx = v.getContext().getApplicationContext();
            Wearable.getMessageClient(ctx)
                .sendMessage(session.originNodeId, PATH_READY, session.sessionId)
                .addOnSuccessListener(id -> FileLog.d("mg-wear-auth: /ready delivered to " + session.originNodeId))
                .addOnFailureListener(e -> {
                    FileLog.e("mg-wear-auth: /ready send failed: " + e.getMessage());
                    cancelQrTimeout();
                    failWaiting(getString("MorsegramWearAuthNoAnswer"));
                });
        });
    }

    /**
     * Chiude il foglio da codice. L'azzeramento di {@link #currentSheet} viene **prima** di
     * {@code dismiss()}: cosi' {@link #onSheetDismissed} riconosce di non essere stato chiamato
     * dall'utente e non butta la sessione.
     */
    public static void cancel() {
        final BottomSheet sheet = currentSheet;
        currentSheet = null;
        acceptButton = null;
        if (sheet != null) sheet.dismiss();
    }

    /**
     * Il foglio e' stato chiuso dall'utente (swipe, tasto indietro, tocco fuori). Senza questo
     * aggancio l'orologio resterebbe ad aspettare fino alla scadenza senza sapere che qualcuno ha
     * rinunciato, e lo stato statico terrebbe in vita un riferimento all'attivita'.
     */
    private static void onSheetDismissed(BottomSheet sheet) {
        if (currentSheet != sheet) return; // chiuso da cancel(), non dall'utente
        currentSheet = null;
        acceptButton = null;
        final AuthSession session = currentSession;
        if (session == null) return;
        currentSession = null;
        cancelQrTimeout();
        cancelShowRetry();
        FileLog.d("mg-wear-auth: foglio chiuso dall'utente; sessione abbandonata");
        if (session.sharedKey != null) {
            sendResult(session, RESULT_GAVE_UP, "");
        } else {
            // Chiuso prima di "Avanti": nessuna chiave, quindi l'unica cosa spedibile e' il rifiuto in chiaro.
            sendDeclined(session);
        }
    }

    // ---- crittografia (rispecchiata in WearAuthBridge.kt) -------------------------------------------

    private static boolean isValidPub(BigInteger v) {
        return v.compareTo(BigInteger.ONE) > 0 && v.compareTo(DH_P.subtract(BigInteger.ONE)) < 0;
    }

    private static byte[] encode256(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == DH_PUBKEY_LEN) return raw;
        if (raw.length == DH_PUBKEY_LEN + 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        if (raw.length < DH_PUBKEY_LEN) {
            byte[] padded = new byte[DH_PUBKEY_LEN];
            System.arraycopy(raw, 0, padded, DH_PUBKEY_LEN - raw.length, raw.length);
            return padded;
        }
        throw new IllegalStateException("unexpected DH value size " + raw.length);
    }

    private static byte[] sha256(byte[]... parts) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] aeadEncrypt(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(AES_GCM_TAG_LEN * 8, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    private static byte[] aeadDecrypt(byte[] key, byte[] iv, byte[] aad, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(AES_GCM_TAG_LEN * 8, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private static String hex(byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static List<String> emojify(byte[] sas, int count) {
        final ArrayList<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(EMOJIS[(int) (bytesToLong(sas, i * 8) % EMOJIS.length)]);
        }
        return out;
    }

    private static long bytesToLong(byte[] a, int off) {
        return (((long) a[off] & 0x7F) << 56)
            | (((long) a[off + 1] & 0xFF) << 48)
            | (((long) a[off + 2] & 0xFF) << 40)
            | (((long) a[off + 3] & 0xFF) << 32)
            | (((long) a[off + 4] & 0xFF) << 24)
            | (((long) a[off + 5] & 0xFF) << 16)
            | (((long) a[off + 6] & 0xFF) << 8)
            | ((long) a[off + 7] & 0xFF);
    }

    /**
     * Tabella SAS: copia verbatim di {@code WearAuthSheet.getEmojis()} di upstream, duplicati
     * inclusi (la lunghezza dell'array entra nel modulo). Deve coincidere riga per riga con
     * {@code WearAuthBridge.EMOJIS} sull'orologio, altrimenti le due schermate mostrano emoji
     * diverse e l'utente annulla un accesso legittimo.
     */
    private static final String[] EMOJIS = new String[] {
        "👋", "👍", "👎", "👌", "👊", "🤟", "🫵", "👏", "🤝", "✍", "💪", "👀", "👅", "🥶",
        "🤡", "💀", "👽", "😈", "😎", "🤠", "🤩", "😍", "🤯", "🦄", "🐶", "🐷", "🐔", "🐥",
        "🦊", "🐙", "🐸", "🐳", "🦉", "🦆", "🐢", "🦖", "🐵", "🐝", "🦁", "🐧", "🦋", "🐬",
        "🦀", "🐌", "🦠", "🐠", "🌵", "💐", "💐", "🎄", "🍄", "🍔", "🍕", "☕", "🍩", "🍪",
        "🎂", "🍫", "🍭", "🍎", "🥥", "🍒", "🌶", "🥒", "🥦", "🍇", "🍋", "🍓", "🍌", "🍍",
        "🍆", "🌽", "🍺", "🍷", "🍾", "🍦", "🍰", "🍞", "🍖", "🌭", "🧊", "🍳", "⭐", "☁",
        "🚀", "🎈", "💎", "💡", "🔑", "❄", "🔎", "👠", "👕", "👗", "👖", "👙", "👜", "👓",
        "🎀", "💄", "💍", "♠", "❤", "♦", "♣", "🌈", "🌊", "🎃", "👻", "🎁", "🔮", "🎥",
        "💿", "💻", "📡", "🔉", "⏳", "🔒", "🚗", "🔱", "🔗", "🎲", "🎮", "⚽", "🎳", "🏁",
        "🏆", "🎸", "💣", "🚽", "🎹", "🎤", "🎨", "🔫", "💊", "💰", "📦", "📅", "📚", "❗",
        "❓", "💯", "💦", "💤", "🌍", "🏝", "🚂", "🛢", "🛹", "🚢", "✈", "🛎", "🧳", "🌖",
        "🌞", "🔥", "🏓", "🎰", "🧸", "🪩", "🎭", "👑", "🎩", "🧢", "🔈", "🔋", "🕯", "✏",
        "💼", "📌", "✂", "🗑", "🛡", "⚙", "🧲", "🪏", "⚖", "🧪", "🚪", "🫧", "🛒", "🪑",
        "🗿", "🏁", "🏴‍☠", "📊", "🥁", "🎧", "🎵", "🧩", "⛳", "🥇", "🥈", "🥈", "🌪", "⛺",
        "🧭", "🫆", "🧠", "💋"
    };
}
