package com.codereview.app.tasks;

import java.time.LocalDate;

public record Task(Long id, String title, String description, boolean completed, LocalDate dueDate) {

    public Task(Long id, String title, String description, boolean completed) {
        this(id, title, description, completed, null);
    }
}
