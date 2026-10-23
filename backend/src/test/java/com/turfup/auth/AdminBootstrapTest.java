package com.turfup.auth;

import com.turfup.PostgresTestBase;
import com.turfup.auth.AdminBootstrap.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The first admin of a real deployment is an existing player account, promoted by TURFUP_ADMIN_PHONE. */
class AdminBootstrapTest extends PostgresTestBase {

    private String role(String phone) {
        return jdbc.sql("SELECT role FROM users WHERE phone = :phone").param("phone", phone)
                .query(String.class).single();
    }

    @Test
    void promotesARegisteredPlayerOnce() {
        createUser("9876500001", "PLAYER");
        AdminBootstrap bootstrap = new AdminBootstrap(jdbc, " 9876500001 ");

        assertThat(bootstrap.promote()).isEqualTo(Outcome.PROMOTED);
        assertThat(role("9876500001")).isEqualTo("ADMIN");
        assertThat(bootstrap.promote()).isEqualTo(Outcome.ALREADY_ADMIN);
    }

    @Test
    void leavesOwnersAndUnknownPhonesAlone() {
        createUser("9876500002", "OWNER");

        assertThat(new AdminBootstrap(jdbc, "9876500002").promote()).isEqualTo(Outcome.NOT_A_PLAYER);
        assertThat(role("9876500002")).isEqualTo("OWNER");
        assertThat(new AdminBootstrap(jdbc, "9876500999").promote()).isEqualTo(Outcome.NOT_REGISTERED);
        assertThat(new AdminBootstrap(jdbc, "").promote()).isEqualTo(Outcome.NOT_SET);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE role = 'ADMIN'").query(Long.class).single()).isZero();
    }
}
