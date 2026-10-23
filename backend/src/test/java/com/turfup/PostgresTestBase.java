package com.turfup;

import com.turfup.auth.Role;
import com.turfup.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Types;

/**
 * Boots the app against a real Postgres 16 container (shared by all test classes through the cached
 * Spring context) and wipes data before each test. The pool is sized so 50 threads can hold
 * connections at once.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=60",
        "turfup.jwt.secret=test-only-secret-that-is-at-least-32-bytes-long",
        "turfup.jobs.enabled=false"})
@AutoConfigureMockMvc
@Import(PostgresTestBase.Containers.class)
public abstract class PostgresTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
        }
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TokenService tokens;

    private int venueCount;

    /**
     * Court ids start at 1001 while venue ids start at 1, so a test (or service) that passes a venue id where a
     * court id belongs fails instead of passing by coincidence.
     */
    static final long FIRST_COURT_ID = 1001;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                TRUNCATE payments, bookings, recurring_skips, recurring_bookings, price_rules, court_sports, courts,
                         venue_customers, subscriptions, venues, cricketers, tournaments, users
                         RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("ALTER SEQUENCE courts_id_seq RESTART WITH " + FIRST_COURT_ID).update();
    }

    protected long createUser(String phone, String role) {
        return jdbc.sql("""
                        INSERT INTO users (name, phone, password_hash, role)
                        VALUES (:name, :phone, 'x', :role) RETURNING id
                        """)
                .param("name", role + " " + phone)
                .param("phone", phone)
                .param("role", role)
                .query(Long.class)
                .single();
    }

    protected long createPlayer(int n) {
        return createUser("9%09d".formatted(n), "PLAYER");
    }

    /** The owner of the court's venue. */
    protected long ownerOf(long courtId) {
        return jdbc.sql("SELECT v.owner_id FROM courts c JOIN venues v ON v.id = c.venue_id WHERE c.id = :id")
                .param("id", courtId).query(Long.class).single();
    }

    protected long venueOf(long courtId) {
        return jdbc.sql("SELECT venue_id FROM courts WHERE id = :id").param("id", courtId).query(Long.class).single();
    }

    /** Authorization header value for a user, as issued at login. */
    protected String bearer(long userId, Role role) {
        return "Bearer " + tokens.issue(userId, role);
    }

    /**
     * An approved venue (new owner) open 6 AM to 2 AM (operating-day minutes 360..1560) with one football court
     * of 60-minute slots, ₹200 advance and no price rules. Returns the court id.
     */
    protected long createCourt() {
        return createCourt(360, 1560, 60);
    }

    protected long createCourt(int openMinute, int closeMinute, int slotMinutes) {
        long ownerId = createUser("8%09d".formatted(++venueCount), "OWNER");
        long venueId = jdbc.sql("""
                        INSERT INTO venues (owner_id, name, area, address, open_minute, close_minute, approved)
                        VALUES (:ownerId, 'Test Venue', 'Cidco', 'N-1, Aurangabad', :open, :close, true)
                        RETURNING id
                        """)
                .param("ownerId", ownerId)
                .param("open", openMinute)
                .param("close", closeMinute)
                .query(Long.class)
                .single();
        return addCourt(venueId, "Court 1", slotMinutes, "football");
    }

    /** Another court at the venue, with the default ₹200 advance and no price rules. Returns its id. */
    protected long addCourt(long venueId, String name, int slotMinutes, String... sports) {
        long courtId = jdbc.sql("""
                        INSERT INTO courts (venue_id, name, slot_minutes, sort_order)
                        VALUES (:venueId, :name, :slot, (SELECT count(*) FROM courts WHERE venue_id = :venueId))
                        RETURNING id
                        """)
                .param("venueId", venueId)
                .param("name", name)
                .param("slot", slotMinutes)
                .query(Long.class)
                .single();
        for (String sport : sports) {
            jdbc.sql("INSERT INTO court_sports (court_id, sport) VALUES (:id, :sport)")
                    .param("id", courtId).param("sport", sport).update();
        }
        return courtId;
    }

    /** {@code dayOfWeek} is ISO (1 = Monday) or null for a general rule; {@code rate} is paise per hour. */
    protected void addPriceRule(long courtId, Integer dayOfWeek, int startMinute, int endMinute, long rate) {
        jdbc.sql("""
                        INSERT INTO price_rules (court_id, day_of_week, start_minute, end_minute, rate)
                        VALUES (:courtId, :day, :start, :end, :rate)
                        """)
                .param("courtId", courtId)
                .param("day", dayOfWeek, Types.SMALLINT)
                .param("start", startMinute)
                .param("end", endMinute)
                .param("rate", rate)
                .update();
    }

    /** The typical tariff: ₹800/hr from opening until 5 PM, ₹1000/hr from 5 PM until 2 AM. */
    protected void addStandardPriceRules(long courtId) {
        addPriceRule(courtId, null, 0, 1020, 80_000);
        addPriceRule(courtId, null, 1020, 2880, 100_000);
    }
}
