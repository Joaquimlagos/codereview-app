package com.codereview.app.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskServiceTest {

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
    void deleteReturnsTrueWhenTaskExisted() {
        Task created = taskService.create(new Task(null, "Title", "desc", false));

        assertThat(taskService.delete(created.id())).isTrue();
        assertThat(taskService.delete(created.id())).isFalse();
    }

    @Test
    void searchMatchesTitleOrDescriptionIgnoringCase() {
        taskService.create(new Task(null, "Buy milk", "groceries", false));
        taskService.create(new Task(null, "Pay rent", "monthly bills", false));
        taskService.create(new Task(null, "Call bank", "about the bill", false));

        List<Task> results = taskService.search("BILL", 0, 10);

        assertThat(results).extracting(Task::title).containsExactly("Pay rent", "Call bank");
    }

    @Test
    void searchReturnsRequestedPage() {
        for (int i = 1; i <= 4; i++) {
            taskService.create(new Task(null, "Task " + i, "desc", false));
        }

        assertThat(taskService.search("task", 1, 2)).extracting(Task::title)
                .containsExactly("Task 3", "Task 4");
    }

    @Test
    void tagSummaryCountsTasksPerTag() {
        taskService.create(new Task(null, "A", "desc", false, List.of("home", "urgent")));
        taskService.create(new Task(null, "B", "desc", false, List.of("home")));

        assertThat(taskService.tagSummary()).isEqualTo(Map.of("home", 2L, "urgent", 1L));
    }

    @Test
    void addTagsMergesWithoutDuplicates() {
        Task created = taskService.create(new Task(null, "Title", "desc", false, List.of("home")));

        Optional<Task> updated = taskService.addTags(created.id(), List.of("home", "urgent"));

        assertThat(updated).isPresent();
        assertThat(updated.get().tags()).containsExactly("home", "urgent");
    }
}
