package com.codereview.app.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
