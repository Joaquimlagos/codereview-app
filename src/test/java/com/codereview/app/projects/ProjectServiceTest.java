package com.codereview.app.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ProjectServiceTest {

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(new InMemoryProjectRepository());
    }

    @Test
    void createStoresAProjectThatStartsUnarchived() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", "moon landing"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Apollo");
        assertThat(created.archived()).isFalse();
    }

    @Test
    void createStripsSurroundingWhitespaceFromTheName() {
        Project created = projectService.create(new CreateProjectRequest("  Apollo  ", null));

        assertThat(created.name()).isEqualTo("Apollo");
    }

    @Test
    void createRejectsABlankName() {
        assertThatExceptionOfType(InvalidProjectException.class)
                .isThrownBy(() -> projectService.create(new CreateProjectRequest("   ", null)))
                .withMessageContaining("must not be blank");
    }

    @Test
    void createRejectsANameOverTheLengthLimit() {
        String tooLong = "x".repeat(81);

        assertThatExceptionOfType(InvalidProjectException.class)
                .isThrownBy(() -> projectService.create(new CreateProjectRequest(tooLong, null)))
                .withMessageContaining("at most 80");
    }

    @Test
    void createRejectsADescriptionOverTheLengthLimit() {
        String tooLong = "x".repeat(501);

        assertThatExceptionOfType(InvalidProjectException.class)
                .isThrownBy(() -> projectService.create(new CreateProjectRequest("Apollo", tooLong)))
                .withMessageContaining("at most 500");
    }

    @Test
    void createRejectsADuplicateNameRegardlessOfCase() {
        projectService.create(new CreateProjectRequest("Apollo", null));

        assertThatExceptionOfType(InvalidProjectException.class)
                .isThrownBy(() -> projectService.create(new CreateProjectRequest("APOLLO", null)))
                .withMessageContaining("already exists");
    }

    @Test
    void findAllHidesArchivedProjectsByDefault() {
        Project live = projectService.create(new CreateProjectRequest("Live", null));
        Project toArchive = projectService.create(new CreateProjectRequest("Old", null));
        projectService.archive(toArchive.id());

        assertThat(projectService.findAll(false)).extracting(Project::id).containsExactly(live.id());
        assertThat(projectService.findAll(true)).hasSize(2);
    }

    @Test
    void updateChangesNameAndDescriptionButKeepsArchivedState() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", "old"));
        projectService.archive(created.id());

        Optional<Project> updated =
                projectService.update(created.id(), new UpdateProjectRequest("Artemis", "new"));

        assertThat(updated).isPresent();
        assertThat(updated.get().name()).isEqualTo("Artemis");
        assertThat(updated.get().description()).isEqualTo("new");
        assertThat(updated.get().archived()).isTrue();
    }

    @Test
    void updateReturnsEmptyWhenTheProjectDoesNotExist() {
        assertThat(projectService.update(404L, new UpdateProjectRequest("Apollo", null))).isEmpty();
    }

    @Test
    void updateAllowsAProjectToKeepItsOwnName() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", "old"));

        Optional<Project> updated =
                projectService.update(created.id(), new UpdateProjectRequest("Apollo", "new"));

        assertThat(updated).isPresent();
        assertThat(updated.get().description()).isEqualTo("new");
    }

    @Test
    void updateRejectsANameTakenByAnotherProject() {
        projectService.create(new CreateProjectRequest("Apollo", null));
        Project other = projectService.create(new CreateProjectRequest("Gemini", null));

        assertThatExceptionOfType(InvalidProjectException.class)
                .isThrownBy(() -> projectService.update(other.id(), new UpdateProjectRequest("Apollo", null)))
                .withMessageContaining("already exists");
    }

    @Test
    void archiveAndUnarchiveFlipTheFlag() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", null));

        assertThat(projectService.archive(created.id())).get().extracting(Project::archived).isEqualTo(true);
        assertThat(projectService.unarchive(created.id())).get().extracting(Project::archived).isEqualTo(false);
    }

    @Test
    void archiveReturnsEmptyForAnUnknownProject() {
        assertThat(projectService.archive(404L)).isEmpty();
        assertThat(projectService.unarchive(404L)).isEmpty();
    }

    @Test
    void deleteRefusesToDropAProjectThatIsStillLive() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", null));

        assertThatExceptionOfType(InvalidProjectException.class)
                .isThrownBy(() -> projectService.delete(created.id()))
                .withMessageContaining("must be archived");

        assertThat(projectService.findById(created.id())).isPresent();
    }

    @Test
    void deleteRemovesAnArchivedProject() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", null));
        projectService.archive(created.id());

        assertThat(projectService.delete(created.id())).isTrue();
        assertThat(projectService.findById(created.id())).isEmpty();
    }

    @Test
    void deleteReturnsFalseForAnUnknownProject() {
        assertThat(projectService.delete(404L)).isFalse();
    }

    @Test
    void aDeletedNameBecomesAvailableAgain() {
        Project created = projectService.create(new CreateProjectRequest("Apollo", null));
        projectService.archive(created.id());
        projectService.delete(created.id());

        Project recreated = projectService.create(new CreateProjectRequest("Apollo", null));

        assertThat(recreated.id()).isNotEqualTo(created.id());
    }
}
