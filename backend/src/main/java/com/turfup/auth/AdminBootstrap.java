package com.turfup.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The first admin of a real deployment. Admins are never self-registered, so the person who runs TurfUp registers
 * in the app as a player with their own phone and password, then sets {@code TURFUP_ADMIN_PHONE} to that phone and
 * restarts: this promotes the account to ADMIN. No admin password ever sits in a config file. Only a PLAYER account
 * is promoted (an owner keeps running their venue); an unknown phone or an owner is reported in the log.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    public enum Outcome { NOT_SET, PROMOTED, ALREADY_ADMIN, NOT_REGISTERED, NOT_A_PLAYER }

    private final JdbcClient jdbc;
    private final String phone;

    public AdminBootstrap(JdbcClient jdbc, @Value("${turfup.admin.phone:}") String phone) {
        this.jdbc = jdbc;
        this.phone = phone == null ? "" : phone.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        promote();
    }

    public Outcome promote() {
        if (phone.isEmpty()) {
            boolean anyAdmin = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN')")
                    .query(Boolean.class).single();
            if (!anyAdmin) {
                log.warn("No admin account yet. Register in the app with your phone, set TURFUP_ADMIN_PHONE to it "
                        + "and restart to make it the admin.");
            }
            return Outcome.NOT_SET;
        }
        String role = jdbc.sql("SELECT role FROM users WHERE phone = :phone")
                .param("phone", phone)
                .query(String.class)
                .optional()
                .orElse(null);
        if (role == null) {
            log.warn("TURFUP_ADMIN_PHONE {} has no account. Register it in the app as a player, then restart.", phone);
            return Outcome.NOT_REGISTERED;
        }
        if (role.equals("ADMIN")) {
            return Outcome.ALREADY_ADMIN;
        }
        if (!role.equals("PLAYER")) {
            log.error("TURFUP_ADMIN_PHONE {} is a {} account; only a player account can be made the admin.", phone, role);
            return Outcome.NOT_A_PLAYER;
        }
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE phone = :phone AND role = 'PLAYER'")
                .param("phone", phone)
                .update();
        log.info("Promoted {} to ADMIN. Log out and in again to use the admin screens.", phone);
        return Outcome.PROMOTED;
    }
}
