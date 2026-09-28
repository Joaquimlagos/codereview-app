package com.codereview.app.projects;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    List<Project> findAll();

    List<Project> findByArchived(boolean archived);

    Optional<Project> findById(Long id);

    /** Lookup is case-insensitive, since project names are how people refer to them. */
    Optional<Project> findByName(String name);

    /** Assigns an id when the project has none, otherwise replaces the stored one. */
    Project save(Project project);

    boolean deleteById(Long id);
}
