package com.codereview.app.tasks;

public record TaskStats(long total, long completed, long pending, long overdue, long completionPercentage) {
}
