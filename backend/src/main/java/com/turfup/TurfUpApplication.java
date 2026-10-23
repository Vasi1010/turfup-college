package com.turfup;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TurfUpApplication {

    public static void main(String[] args) {
        // Windows reports the legacy alias "Asia/Calcutta", which the JDBC driver sends to Postgres
        // as the session TimeZone and newer Postgres images reject. Pin the canonical IST zone.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(TurfUpApplication.class, args);
    }
}
