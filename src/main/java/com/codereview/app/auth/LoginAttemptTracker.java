package com.codereview.app.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Counts failed logins per username and locks the account for a while once
 * the limit is reached. State is held in memory and lost on restart.
 */
@Component
public class LoginAttemptTracker {

    static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Map<String, Integer> failedAttempts = new HashMap<>();
    private final Map<String, Instant> lockedUntil = new HashMap<>();

    public boolean isLocked(String username) {
        Instant until = lockedUntil.get(username);
        return until != null && Instant.now().isBefore(until);
    }

    public void recordFailure(String username) {
        int attempts = failedAttempts.getOrDefault(username, 0) + 1;
        failedAttempts.put(username, attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil.put(username, Instant.now().plus(LOCK_DURATION));
        }
    }

    public void recordSuccess(String username) {
        failedAttempts.remove(username);
    }

    public int remainingAttempts(String username) {
        return MAX_FAILED_ATTEMPTS - failedAttempts.getOrDefault(username, 0);
    }
}
