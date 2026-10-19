package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

import org.telegram.messenger.BuildVars;

/**
 * Android Auto "templated messaging" entry point (Car App Library, category MESSAGING).
 * Labs only: declared by the opt-in module TMessagesProj_CarLabs, never in production builds.
 * Derived from upstream org.telegram.messenger.car.TelegramCarAppService (androidx.car.app 1.4.0 -> 1.7.0).
 */
public class MorsegramCarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        if (BuildVars.DEBUG_VERSION) {
            // Debug: also accept the Desktop Head Unit / unsigned hosts ("Unknown sources" in Android Auto developer settings).
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
