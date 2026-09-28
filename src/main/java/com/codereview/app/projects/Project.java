package com.codereview.app.projects;

public record Project(Long id, String name, String description, boolean archived) {

    public Project withId(Long newId) {
        return new Project(newId, name, description, archived);
    }

    public Project withArchived(boolean newArchived) {
        return new Project(id, name, description, newArchived);
    }
}
