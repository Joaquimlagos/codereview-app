package com.codereview.app.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtValidatorTest {

    private JwtValidator jwtValidator;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator("test-secret-key-for-unit-tests-32-bytes-min", 60);
    }

    @Test
    void generatedTokenIsValid() {
        String token = jwtValidator.generateToken("admin");

        assertThat(jwtValidator.isValid(token)).isTrue();
    }

    @Test
    void extractUsernameReturnsSubject() {
        String token = jwtValidator.generateToken("admin");

        assertThat(jwtValidator.extractUsername(token)).contains("admin");
    }

    @Test
    void invalidTokenIsRejected() {
        assertThat(jwtValidator.isValid("not-a-real-token")).isFalse();
    }
}
