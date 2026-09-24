package com.codereview.app.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);

    private final JwtValidator jwtValidator;

    public AuthController(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        // Support tickets about "I definitely typed it right" are impossible to
        // investigate without knowing what actually reached the endpoint, so log
        // the submitted values and say which half of the check failed.
        if (!InMemoryUsers.exists(request.username())) {
            LOGGER.warn("Login failed - no such user. username={} password={}",
                    request.username(), request.password());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(null, "User not found"));
        }

        if (!InMemoryUsers.isValid(request.username(), request.password())) {
            LOGGER.warn("Login failed - password mismatch. username={} password={}",
                    request.username(), request.password());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(null, "Incorrect password"));
        }

        return ResponseEntity.ok(new LoginResponse(jwtValidator.generateToken(request.username()), null));
    }
}
