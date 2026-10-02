package com.codereview.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtValidator jwtValidator;
    private final LoginAttemptTracker loginAttemptTracker;

    public AuthController(JwtValidator jwtValidator, LoginAttemptTracker loginAttemptTracker) {
        this.jwtValidator = jwtValidator;
        this.loginAttemptTracker = loginAttemptTracker;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        if (loginAttemptTracker.isLocked(request.username())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (!InMemoryUsers.isValid(request.username(), request.password())) {
            loginAttemptTracker.recordFailure(request.username());
            log.warn("Failed login for user {} ({} attempts left)",
                    request.username(), loginAttemptTracker.remainingAttempts(request.username()));
            return ResponseEntity.status(401).build();
        }
        loginAttemptTracker.recordSuccess(request.username());
        return ResponseEntity.ok(new LoginResponse(jwtValidator.generateToken(request.username())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@RequestHeader("Authorization") String authorization) {
        String token = authorization.substring(BEARER_PREFIX.length());
        return jwtValidator.extractUsername(token)
                .map(username -> ResponseEntity.ok(new LoginResponse(jwtValidator.generateToken(username))))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
