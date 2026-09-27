package com.codereview.app.auth;

import java.util.Map;

/**
 * Fixed user store held in memory: passwords are compared in plaintext and
 * nothing is persisted across restarts.
 */
final class InMemoryUsers {

    private static final Map<String, String> CREDENTIALS = Map.of(
            "admin", "admin123"
    );

    private InMemoryUsers() {
    }

    static boolean isValid(String username, String password) {
        return CREDENTIALS.getOrDefault(username, "").equals(password);
    }
}
