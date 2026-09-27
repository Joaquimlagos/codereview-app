package com.codereview.app.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtValidator jwtValidator;

    public AuthController(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        if (!InMemoryUsers.isValid(request.username(), request.password())) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new LoginResponse(jwtValidator.generateToken(request.username())));
    }
}
