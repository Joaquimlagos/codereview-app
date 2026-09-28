package com.codereview.app.projects;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class InMemoryProjectRepository implements ProjectRepository {

    private final Map<Long, Project> projects = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public List<Project> findAll() {
        return projects.values().stream()
                .sorted(Comparator.comparing(Project::id))
                .toList();
    }

    @Override
    public List<Project> findByArchived(boolean archived) {
        return projects.values().stream()
                .filter(project -> project.archived() == archived)
                .sorted(Comparator.comparing(Project::id))
                .toList();
    }

    @Override
    public Optional<Project> findById(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(projects.get(id));
    }

    @Override
    public Optional<Project> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return projects.values().stream()
                .filter(project -> project.name().equalsIgnoreCase(name))
                .findFirst();
    }

    @Override
    public Project save(Project project) {
        Project stored = project.id() == null
                ? project.withId(nextId.getAndIncrement())
                : project;
        projects.put(stored.id(), stored);
        return stored;
    }

    @Override
    public boolean deleteById(Long id) {
        return id != null && projects.remove(id) != null;
    }
}
