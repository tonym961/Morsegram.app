package it.iotatec.morsegram.wear;

import android.content.Intent;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.LaunchActivity;

/**
 * Consegna i messaggi del ponte di accesso dall'orologio a {@link MorsegramWearAuth}.
 *
 * <p>Ascolta <b>solo</b> il prefisso {@code /mg-wear-auth/}: {@code /tg-wear-auth/} resta a
 * {@code org.telegram.messenger.WearAuthListenerService} di upstream, che non tocchiamo e che non
 * riceve piu' nulla (l'orologio Morsegram non parla piu' quel protocollo). Dichiarato nel manifest
 * del modulo app, dove stanno gli altri nostri componenti.
 *
 * <p>Come il servizio di upstream, all'arrivo dell'offer porta {@code LaunchActivity} in primo
 * piano: il foglio di conferma vive su un'attivita', e se l'app e' in background non si vedrebbe.
 */
public class MorsegramWearAuthService extends WearableListenerService {

    @Override
    public void onMessageReceived(@NonNull MessageEvent event) {
        final String path = event.getPath();
        final String node = event.getSourceNodeId();
        final byte[] data = event.getData();
        if (MorsegramWearAuth.PATH_OFFER.equals(path)) {
            try {
                Intent intent = new Intent(this, LaunchActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intent);
            } catch (Exception e) {
                FileLog.e("mg-wear-auth: failed to pop LaunchActivity", e);
            }
        }
        AndroidUtilities.runOnUIThread(() -> {
            switch (path) {
                case MorsegramWearAuth.PATH_OFFER:
                    FileLog.d("mg-wear-auth: offer from " + node + " (" + data.length + " bytes)");
                    MorsegramWearAuth.onOfferReceived(data, node);
                    break;
                case MorsegramWearAuth.PATH_QR:
                    FileLog.d("mg-wear-auth: qr from " + node + " (" + data.length + " bytes)");
                    MorsegramWearAuth.onQrReceived(data, node);
                    break;
                case MorsegramWearAuth.PATH_CANCEL:
                    FileLog.d("mg-wear-auth: cancel from " + node);
                    MorsegramWearAuth.onCancelReceived(data);
                    break;
                default:
                    FileLog.d("mg-wear-auth: unexpected path " + path);
                    break;
            }
        });
    }
}
