package com.turfup.auth;

import com.turfup.auth.AuthService.AuthResult;
import com.turfup.auth.AuthService.RegisterCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** Indian mobile number, 10 digits, no country code. */
    static final String PHONE = "^[6-9][0-9]{9}$";

    record RegisterRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = PHONE, message = "must be a 10-digit mobile number") String phone,
            @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            Role role) {
    }

    record LoginRequest(@NotBlank String phone, @NotBlank String password) {
    }

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** {@code role} is PLAYER (default) or OWNER. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResult register(@Valid @RequestBody RegisterRequest req) {
        Role role = req.role() == null ? Role.PLAYER : req.role();
        return auth.register(new RegisterCommand(req.name(), req.phone(), req.email(), req.password(), role));
    }

    @PostMapping("/login")
    AuthResult login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.phone(), req.password());
    }
}
