package com.codereview.app.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryProjectRepositoryTest {

    private InMemoryProjectRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryProjectRepository();
    }

    @Test
    void saveAssignsIncrementalIdsWhenIdIsNull() {
        Project first = repository.save(new Project(null, "Apollo", "first", false));
        Project second = repository.save(new Project(null, "Gemini", "second", false));

        assertThat(first.id()).isEqualTo(1L);
        assertThat(second.id()).isEqualTo(2L);
    }

    @Test
    void saveReplacesTheStoredProjectWhenIdIsPresent() {
        Project created = repository.save(new Project(null, "Apollo", "first", false));

        Project renamed = repository.save(new Project(created.id(), "Apollo II", "first", false));

        assertThat(renamed.id()).isEqualTo(created.id());
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findById(created.id()))
                .get()
                .extracting(Project::name)
                .isEqualTo("Apollo II");
    }

    @Test
    void findAllIsOrderedById() {
        repository.save(new Project(null, "C", null, false));
        repository.save(new Project(null, "A", null, false));
        repository.save(new Project(null, "B", null, false));

        assertThat(repository.findAll()).extracting(Project::name).containsExactly("C", "A", "B");
    }

    @Test
    void findByArchivedSplitsLiveFromArchived() {
        Project live = repository.save(new Project(null, "Live", null, false));
        Project archived = repository.save(new Project(null, "Archived", null, true));

        assertThat(repository.findByArchived(false)).containsExactly(live);
        assertThat(repository.findByArchived(true)).containsExactly(archived);
    }

    @Test
    void findByNameIgnoresCase() {
        repository.save(new Project(null, "Apollo", null, false));

        assertThat(repository.findByName("APOLLO")).isPresent();
        assertThat(repository.findByName("apollo")).isPresent();
        assertThat(repository.findByName("apollo 2")).isEmpty();
    }

    @Test
    void lookupsTolerateNullWithoutThrowing() {
        assertThat(repository.findById(null)).isEmpty();
        assertThat(repository.findByName(null)).isEmpty();
        assertThat(repository.deleteById(null)).isFalse();
    }

    @Test
    void deleteByIdReportsWhetherSomethingWasRemoved() {
        Project created = repository.save(new Project(null, "Apollo", null, false));

        assertThat(repository.deleteById(created.id())).isTrue();
        assertThat(repository.deleteById(created.id())).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void findAllReturnsEmptyListOnAFreshRepository() {
        assertThat(repository.findAll()).isEqualTo(List.of());
    }
}
