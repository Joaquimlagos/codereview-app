package com.codereview.app.tasks;

import java.util.List;

public record Task(Long id, String title, String description, boolean completed, List<String> tags) {

    public Task {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public Task(Long id, String title, String description, boolean completed) {
        this(id, title, description, completed, List.of());
    }

    public Task withTags(List<String> newTags) {
        return new Task(id, title, description, completed, newTags);
    }
}
