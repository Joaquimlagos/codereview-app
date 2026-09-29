package com.codereview.app.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService();
    }

    @Test
    void createAssignsIncrementalId() {
        Task first = taskService.create(new Task(null, "First", "desc", false));
        Task second = taskService.create(new Task(null, "Second", "desc", false));

        assertThat(first.id()).isEqualTo(1L);
        assertThat(second.id()).isEqualTo(2L);
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        assertThat(taskService.findById(99L)).isEmpty();
    }

    @Test
    void updateReturnsEmptyWhenTaskDoesNotExist() {
        assertThat(taskService.update(99L, new Task(null, "x", "y", false))).isEmpty();
    }

    @Test
    void updateReplacesExistingTask() {
        Task created = taskService.create(new Task(null, "Title", "desc", false));

        Optional<Task> updated = taskService.update(created.id(), new Task(null, "Updated", "desc", true));

        assertThat(updated).isPresent();
        assertThat(updated.get().title()).isEqualTo("Updated");
        assertThat(updated.get().completed()).isTrue();
    }

    @Test
    void createKeepsDueDate() {
        Task created = taskService.create(new Task(null, "Title", "desc", false, TODAY.plusDays(3)));

        assertThat(created.dueDate()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void updateChangesDueDate() {
        Task created = taskService.create(new Task(null, "Title", "desc", false, TODAY.plusDays(3)));

        Optional<Task> updated = taskService.update(created.id(),
                new Task(null, "Title", "desc", false, TODAY.plusDays(10)));

        assertThat(updated).isPresent();
        assertThat(updated.get().dueDate()).isEqualTo(TODAY.plusDays(10));
    }

    @Test
    void findOverdueReturnsPendingTasksPastTheirDueDate() {
        taskService.create(new Task(null, "Late", "desc", false, TODAY.minusDays(2)));
        taskService.create(new Task(null, "Done late", "desc", true, TODAY.minusDays(2)));
        taskService.create(new Task(null, "Upcoming", "desc", false, TODAY.plusDays(5)));

        List<Task> overdue = taskService.findOverdue(TODAY);

        assertThat(overdue).extracting(Task::title).containsExactly("Late");
    }

    @Test
    void completeAllMarksEveryTaskAsCompleted() {
        Task first = taskService.create(new Task(null, "First", "desc", false, TODAY.plusDays(1)));
        Task second = taskService.create(new Task(null, "Second", "desc", false, TODAY.plusDays(2)));

        List<Task> completed = taskService.completeAll(List.of(first.id(), second.id()));

        assertThat(completed).hasSize(2).allMatch(Task::completed);
        assertThat(taskService.findById(first.id())).get().extracting(Task::completed).isEqualTo(true);
        assertThat(taskService.findById(second.id())).get().extracting(Task::dueDate).isEqualTo(TODAY.plusDays(2));
    }

    @Test
    void getStatsCountsTasksByState() {
        taskService.create(new Task(null, "Done", "desc", true, TODAY.minusDays(1)));
        taskService.create(new Task(null, "Late", "desc", false, TODAY.minusDays(3)));
        taskService.create(new Task(null, "Upcoming", "desc", false, TODAY.plusDays(4)));
        taskService.create(new Task(null, "Also done", "desc", true, TODAY.plusDays(1)));

        TaskStats stats = taskService.getStats(TODAY);

        assertThat(stats).isEqualTo(new TaskStats(4, 2, 2, 1, 50));
    }

    @Test
    void deleteReturnsTrueWhenTaskExisted() {
        Task created = taskService.create(new Task(null, "Title", "desc", false));

        assertThat(taskService.delete(created.id())).isTrue();
        assertThat(taskService.delete(created.id())).isFalse();
    }
}
