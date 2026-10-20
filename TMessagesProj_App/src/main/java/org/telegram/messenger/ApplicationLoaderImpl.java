package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
    @Override public void onCreate() { super.onCreate(); it.iotatec.morsegram.MorsegramApp.onCreate(this, org.telegram.messenger.regular.R.drawable.morsegram_airplane, org.telegram.messenger.regular.R.drawable.morsegram_logo, org.telegram.messenger.regular.R.drawable.morsegram_pin_local); } // <morsegram> unico bootstrap: gli id drawable del modulo app entrano nella libreria solo da qui </morsegram>
    @Override public void addItemOptions(org.telegram.ui.Components.ItemOptions io) { it.iotatec.morsegram.MorsegramApp.addItemOptions(io); } // <morsegram> voci 'Modalità aereo' + 'Morsegram' nel menu ⋯ della lista chat (extension point DialogsActivity) </morsegram>
    @Override public boolean consumePush(int account, org.json.JSONObject json) { return it.iotatec.morsegram.AirplaneMode.isOn(); } // <morsegram> in modalità aereo le push FCM vengono ignorate (extension point PushListenerController); getDifference le rigenera al rientro </morsegram>
    @Override public void onResume() { it.iotatec.morsegram.MorsegramApp.onResume(); } // <morsegram> riallinea notifica persistente e tile QS (extension point LaunchActivity.onResume) </morsegram>
}
