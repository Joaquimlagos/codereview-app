package com.codereview.app.auth;

import java.util.Map;

/**
 * Fixed, in-memory user store. Deliberately unsophisticated (plaintext
 * password comparison, no persistence) — this module exists to generate
 * "hard" complexity review PRs later, not to be production-grade auth.
 */
final class InMemoryUsers {

    private static final Map<String, String> CREDENTIALS = Map.of(
            "admin", "admin123"
    );

    private InMemoryUsers() {
    }

    static boolean exists(String username) {
        return CREDENTIALS.containsKey(username);
    }

    static boolean isValid(String username, String password) {
        return CREDENTIALS.getOrDefault(username, "").equals(password);
    }
}
