package com.turfup.demo;

import com.turfup.pricing.PriceRule;
import com.turfup.venue.CourtSetup;
import com.turfup.venue.VenueApprovalBlockedException;
import com.turfup.venue.VenueApprovalService;
import com.turfup.venue.VenueSetup;
import com.turfup.venue.VenueSetupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Local dev / demo data, only with the {@code demo} profile: one admin and three owners, each with an approved
 * multi-sport venue: Kick Arena (a football/cricket turf, two badminton courts), Street Sports Arena (a box
 * cricket/football turf, a pickleball court) and The Pavilion Arena (a cricket/football turf, a tennis court).
 * Idempotent: existing accounts (matched by phone) and an owner who already has a venue are left alone, so a
 * database seeded before a venue was added gets it on the next start.
 * The passwords are public; never enable this profile on a real deployment.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String ADMIN_PHONE = "9999999999";
    public static final String ADMIN_PASSWORD = "admin-demo-123";
    public static final String OWNER_PHONE = "8888888888";
    public static final String OWNER_PASSWORD = "owner-demo-123";
    /** Owners of the two other demo venues (same password). */
    public static final String STREET_OWNER_PHONE = "8888888881";
    public static final String PAVILION_OWNER_PHONE = "8888888882";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final VenueSetupService venues;
    private final VenueApprovalService approvals;
    private final ApplicationContext context;

    public DemoDataSeeder(JdbcClient jdbc, PasswordEncoder passwords, VenueSetupService venues,
                          VenueApprovalService approvals, ApplicationContext context) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.venues = venues;
        this.approvals = approvals;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
        // With the tournament module on: a demo cricket tournament for showing live scoring.
        context.getBeanProvider(DemoTournament.class).ifAvailable(DemoTournament::seed);
        if (context.getEnvironment().getProperty("turfup.demo.showcase", Boolean.class, false)) {
            // scripts/reset-demo (TURFUP_DEMO_SHOWCASE=true): today's and tomorrow's bookings for showing the app
            // to turf owners. Off by default: the e2e walkthrough books this venue too.
            context.getBean(DemoShowcase.class).seed();
        }
        if (args.containsOption("turfup.demo.seed-only")) {
            // scripts/reset-demo: seed a fresh database, then stop instead of serving.
            log.info("Demo data seeded; exiting (seed-only).");
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    public void seed() {
        user("TurfUp Admin", ADMIN_PHONE, ADMIN_PASSWORD, "ADMIN");
        String policy = "Full advance refunded if you cancel 24 hours before the slot, once a month.";

        // Kick Arena, Cidco: 6 AM to 2 AM.
        List<PriceRule> pitch = rates(1560, 80_000, 100_000);          // ₹800/hr until 5 PM, ₹1,000 after
        List<PriceRule> badminton = rates(1560, 30_000, 40_000);       // ₹300 / ₹400
        venue("Imran Shaikh", OWNER_PHONE, new VenueSetup(
                        "Kick Arena", "Cidco", "N-5, near Kamgar Chowk, Cidco, Aurangabad",
                        new BigDecimal("19.876165"), new BigDecimal("75.343314"), 360, 1560, 1),
                List.of(
                        new CourtSetup("Main turf", List.of("football", "cricket"), 60, 20_000, 24, policy, true, pitch),
                        new CourtSetup("Badminton 1", List.of("badminton"), 60, 10_000, 24, policy, true, badminton),
                        new CourtSetup("Badminton 2", List.of("badminton"), 60, 10_000, 24, policy, true, badminton)));

        // Street Sports Arena, Garkheda: 6 AM to 1 AM.
        venue("Sagar Jadhav", STREET_OWNER_PHONE, new VenueSetup(
                        "Street Sports Arena", "Garkheda", "Garkheda, Aurangabad",
                        new BigDecimal("19.859000"), new BigDecimal("75.333000"), 360, 1500, 1),
                List.of(
                        new CourtSetup("Box turf", List.of("box-cricket", "football"), 60, 20_000, 24, policy, true,
                                rates(1500, 70_000, 90_000)),                  // ₹700 / ₹900
                        new CourtSetup("Pickleball court", List.of("pickleball"), 60, 10_000, 24, policy, true,
                                rates(1500, 30_000, 40_000))));                // ₹300 / ₹400

        // The Pavilion Arena, Altamash Colony: 6 AM to midnight. No coordinates (none known for it yet).
        venue("Farhan Siddiqui", PAVILION_OWNER_PHONE, new VenueSetup(
                        "The Pavilion Arena", "Altamash Colony", "Altamash Colony, Aurangabad",
                        null, null, 360, 1440, 1),
                List.of(
                        new CourtSetup("Cricket turf", List.of("cricket", "football"), 60, 30_000, 24, policy, true,
                                rates(1440, 100_000, 120_000)),                // ₹1,000 / ₹1,200
                        new CourtSetup("Tennis court", List.of("tennis"), 60, 10_000, 24, policy, true,
                                rates(1440, 40_000, 50_000))));                // ₹400 / ₹500

        log.info("Demo logins: admin {} / {}, owners {}, {}, {} / {}", ADMIN_PHONE, ADMIN_PASSWORD, OWNER_PHONE,
                STREET_OWNER_PHONE, PAVILION_OWNER_PHONE, OWNER_PASSWORD);
    }

    /** One rate from opening (6 AM) to 5 PM and another from 5 PM to {@code closeMinute}. */
    private static List<PriceRule> rates(int closeMinute, long day, long evening) {
        return List.of(new PriceRule(null, 360, 1020, day), new PriceRule(null, 1020, closeMinute, evening));
    }

    /** Creates and approves the owner's venue, unless the owner already has one. */
    private void venue(String ownerName, String ownerPhone, VenueSetup setup, List<CourtSetup> courts) {
        long ownerId = user(ownerName, ownerPhone, OWNER_PASSWORD, "OWNER");
        boolean hasVenue = jdbc.sql("SELECT EXISTS (SELECT 1 FROM venues WHERE owner_id = :ownerId)")
                .param("ownerId", ownerId)
                .query(Boolean.class)
                .single();
        if (hasVenue) {
            return;
        }
        var venue = venues.create(ownerId, setup, courts);
        try {
            approvals.setApproved(venue.id(), true);
            log.info("Demo data: created approved venue {} ({}) for owner {}", venue.id(), setup.name(), ownerPhone);
        } catch (VenueApprovalBlockedException e) {
            // Razorpay mode: an admin links the owner's Razorpay account first, then approves.
            log.info("Demo data: created venue {} ({}) for owner {}; link its Razorpay account, then approve it",
                    venue.id(), setup.name(), ownerPhone);
        }
    }

    private long user(String name, String phone, String password, String role) {
        return jdbc.sql("SELECT id FROM users WHERE phone = :phone")
                .param("phone", phone)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql("""
                                INSERT INTO users (name, phone, password_hash, role)
                                VALUES (:name, :phone, :hash, :role)
                                RETURNING id
                                """)
                        .param("name", name)
                        .param("phone", phone)
                        .param("hash", passwords.encode(password))
                        .param("role", role)
                        .query(Long.class)
                        .single());
    }
}
