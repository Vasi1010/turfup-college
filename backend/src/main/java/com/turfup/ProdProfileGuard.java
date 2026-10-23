package com.turfup;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when {@code prod} and {@code demo} are both active: the demo seeder creates accounts with
 * public passwords, which must never exist on a real deployment.
 */
@Component
@Profile("prod & demo")
class ProdProfileGuard {

    ProdProfileGuard() {
        throw new IllegalStateException("The demo profile must not be active together with prod.");
    }
}
