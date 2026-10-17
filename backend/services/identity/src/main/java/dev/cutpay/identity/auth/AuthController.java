package dev.cutpay.identity.auth;

import dev.cutpay.identity.auth.AuthDtos.AuthResponse;
import dev.cutpay.identity.auth.AuthDtos.LoginRequest;
import dev.cutpay.identity.auth.AuthDtos.MeResponse;
import dev.cutpay.identity.auth.AuthDtos.SignupRequest;
import dev.cutpay.identity.security.AuthProperties;
import dev.cutpay.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    static final String REFRESH_COOKIE = "cutpay_rt";
    private static final String COOKIE_PATH = "/auth";

    private final AuthService auth;
    private final AuthProperties properties;

    public AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/auth/signup")
    ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return withSession(HttpStatus.CREATED, auth.signup(request));
    }

    @PostMapping("/auth/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withSession(HttpStatus.OK, auth.login(request));
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            throw ApiException.unauthorized("missing_refresh_token", "Please sign in again");
        }
        return withSession(HttpStatus.OK, auth.refresh(refreshToken));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", 0).toString())
                .build();
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(jwt.getSubject());
    }

    private ResponseEntity<AuthResponse> withSession(HttpStatus status, AuthService.Session session) {
        long maxAge = properties.refreshTokenTtl().toSeconds();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), maxAge).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(session.response());
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }
}
