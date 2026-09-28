package com.codereview.app.projects;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<Project> findAll(boolean includeArchived) {
        return includeArchived ? repository.findAll() : repository.findByArchived(false);
    }

    public Optional<Project> findById(Long id) {
        return repository.findById(id);
    }

    public Project create(CreateProjectRequest request) {
        String name = normalize(request.name());
        validateName(name);
        validateDescription(request.description());
        requireNameAvailable(name, null);

        return repository.save(new Project(null, name, request.description(), false));
    }

    public Optional<Project> update(Long id, UpdateProjectRequest request) {
        String name = normalize(request.name());
        validateName(name);
        validateDescription(request.description());

        Optional<Project> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        requireNameAvailable(name, id);
        Project current = existing.get();
        return Optional.of(repository.save(
                new Project(current.id(), name, request.description(), current.archived())));
    }

    public Optional<Project> archive(Long id) {
        return setArchived(id, true);
    }

    public Optional<Project> unarchive(Long id) {
        return setArchived(id, false);
    }

    /**
     * Deleting a project is only allowed once it has been archived: archiving is the
     * reversible step, so making it a precondition means a live project cannot be
     * dropped by a single mistaken call.
     */
    public boolean delete(Long id) {
        Optional<Project> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return false;
        }
        if (!existing.get().archived()) {
            throw new InvalidProjectException("project must be archived before it can be deleted");
        }
        return repository.deleteById(id);
    }

    private Optional<Project> setArchived(Long id, boolean archived) {
        return repository.findById(id)
                .map(project -> repository.save(project.withArchived(archived)));
    }

    private void requireNameAvailable(String name, Long allowedId) {
        repository.findByName(name)
                .filter(other -> !other.id().equals(allowedId))
                .ifPresent(other -> {
                    throw new InvalidProjectException(
                            "a project named '" + other.name() + "' already exists");
                });
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidProjectException("name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidProjectException(
                    "name must be at most " + MAX_NAME_LENGTH + " characters");
        }
    }

    private void validateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidProjectException(
                    "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
    }

    private String normalize(String name) {
        return name == null ? null : name.strip();
    }
}
