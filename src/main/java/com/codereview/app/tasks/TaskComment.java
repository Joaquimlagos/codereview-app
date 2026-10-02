package com.codereview.app.tasks;

import java.time.Instant;

public record TaskComment(Long id, Long taskId, String author, String text, Instant createdAt) {
}
