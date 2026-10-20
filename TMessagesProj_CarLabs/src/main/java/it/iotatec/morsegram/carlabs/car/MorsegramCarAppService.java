package it.iotatec.morsegram.carlabs.car;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * Android Auto "templated messaging" entry point (Car App Library, category MESSAGING).
 * Labs only: declared by the opt-in module TMessagesProj_CarLabs, never in production builds.
 * Derived from upstream org.telegram.messenger.car.TelegramCarAppService (androidx.car.app 1.4.0 -> 1.7.0).
 */
public class MorsegramCarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Only a DEBUGGABLE build accepts any host (Desktop Head Unit / "Unknown sources"), which is also how the
        // Car App Library's own snippet gates it. NOT BuildVars.DEBUG_VERSION: that constant is true for the
        // release-signed HA_private/HA_public/HA_hardcore types too, i.e. for the labs APK testers actually
        // install, and this service is exported — any app on the phone could then bind as a "host" and read our
        // templates (chat titles, message previews) or call ConversationCallback.onTextReply() to send messages.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        // Only Google-published Android Auto / Automotive hosts (signed by Google).
        return new HostValidator.Builder(getApplicationContext())
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new MorsegramCarSession();
    }
}
