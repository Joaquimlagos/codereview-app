package com.codereview.app.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptTrackerTest {

    private LoginAttemptTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LoginAttemptTracker();
    }

    @Test
    void isNotLockedBeforeAnyFailure() {
        assertThat(tracker.isLocked("admin")).isFalse();
        assertThat(tracker.remainingAttempts("admin")).isEqualTo(LoginAttemptTracker.MAX_FAILED_ATTEMPTS);
    }

    @Test
    void staysUnlockedBelowTheLimit() {
        for (int i = 0; i < LoginAttemptTracker.MAX_FAILED_ATTEMPTS - 1; i++) {
            tracker.recordFailure("admin");
        }

        assertThat(tracker.isLocked("admin")).isFalse();
        assertThat(tracker.remainingAttempts("admin")).isEqualTo(1);
    }

    @Test
    void locksOnceTheLimitIsReached() {
        for (int i = 0; i < LoginAttemptTracker.MAX_FAILED_ATTEMPTS; i++) {
            tracker.recordFailure("admin");
        }

        assertThat(tracker.isLocked("admin")).isTrue();
    }

    @Test
    void successResetsTheFailureCount() {
        tracker.recordFailure("admin");
        tracker.recordFailure("admin");

        tracker.recordSuccess("admin");

        assertThat(tracker.remainingAttempts("admin")).isEqualTo(LoginAttemptTracker.MAX_FAILED_ATTEMPTS);
    }

    @Test
    void failuresAreTrackedPerUsername() {
        for (int i = 0; i < LoginAttemptTracker.MAX_FAILED_ATTEMPTS; i++) {
            tracker.recordFailure("admin");
        }

        assertThat(tracker.isLocked("someone-else")).isFalse();
    }
}
