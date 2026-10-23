package com.turfup.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and login by phone + password. */
@Service
public class AuthService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    /** Compared against when the phone is unknown, so both failure cases take the same time. */
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwords, TokenService tokens) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode("dummy-password-for-timing");
    }

    public record RegisterCommand(String name, String phone, String email, String password, Role role) {
    }

    public record AuthResult(String token, long userId, String name, Role role) {
    }

    record UserRow(long id, String name, String role, String passwordHash) {
    }

    /**
     * Creates a PLAYER or OWNER account and logs it in. Admins are never self-registered.
     *
     * @throws PhoneAlreadyRegisteredException if the phone already has an account
     */
    @Transactional
    public AuthResult register(RegisterCommand cmd) {
        if (cmd.role() == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot register as ADMIN");
        }
        long id = jdbc.sql("""
                        INSERT INTO users (name, phone, email, password_hash, role)
                        VALUES (:name, :phone, :email, :hash, :role)
                        ON CONFLICT (phone) DO NOTHING
                        RETURNING id
                        """)
                .param("name", cmd.name().strip())
                .param("phone", cmd.phone())
                .param("email", cmd.email())
                .param("hash", passwords.encode(cmd.password()))
                .param("role", cmd.role().name())
                .query(Long.class)
                .optional()
                .orElseThrow(PhoneAlreadyRegisteredException::new);
        return new AuthResult(tokens.issue(id, cmd.role()), id, cmd.name().strip(), cmd.role());
    }

    /** @throws InvalidCredentialsException if the phone is unknown or the password is wrong */
    @Transactional(readOnly = true)
    public AuthResult login(String phone, String password) {
        UserRow user = jdbc.sql("SELECT id, name, role, password_hash FROM users WHERE phone = :phone")
                .param("phone", phone)
                .query(UserRow.class)
                .optional()
                .orElse(null);
        if (user == null) {
            passwords.matches(password, dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwords.matches(password, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        Role role = Role.valueOf(user.role());
        return new AuthResult(tokens.issue(user.id(), role), user.id(), user.name(), role);
    }
}
