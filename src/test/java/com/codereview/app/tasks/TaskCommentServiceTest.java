package com.codereview.app.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskCommentServiceTest {

    private TaskService taskService;
    private TaskCommentService taskCommentService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService();
        taskCommentService = new TaskCommentService(taskService);
    }

    @Test
    void addAssignsIdTaskAndTimestamp() {
        Task task = taskService.create(new Task(null, "Title", "desc", false));

        TaskComment created = taskCommentService.add(task.id(), new TaskComment(null, null, "ana", "First", null));

        assertThat(created.id()).isEqualTo(1L);
        assertThat(created.taskId()).isEqualTo(task.id());
        assertThat(created.createdAt()).isNotNull();
    }

    @Test
    void addReturnsNullWhenTaskDoesNotExist() {
        assertThat(taskCommentService.add(99L, new TaskComment(null, null, "ana", "First", null))).isNull();
    }

    @Test
    void findByTaskReturnsOnlyThatTasksComments() {
        Task first = taskService.create(new Task(null, "First", "desc", false));
        Task second = taskService.create(new Task(null, "Second", "desc", false));
        taskCommentService.add(first.id(), new TaskComment(null, null, "ana", "On first", null));
        taskCommentService.add(second.id(), new TaskComment(null, null, "bob", "On second", null));

        List<TaskComment> comments = taskCommentService.findByTask(first.id());

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).text()).isEqualTo("On first");
    }

    @Test
    void findByTaskReturnsEmptyWhenThereAreNoComments() {
        assertThat(taskCommentService.findByTask(99L)).isEmpty();
    }

    @Test
    void deleteRemovesTheComment() {
        Task task = taskService.create(new Task(null, "Title", "desc", false));
        TaskComment created = taskCommentService.add(task.id(), new TaskComment(null, null, "ana", "First", null));

        assertThat(taskCommentService.delete(task.id(), created.id())).isTrue();
        assertThat(taskCommentService.delete(task.id(), created.id())).isFalse();
    }

    @Test
    void recentLimitsTheNumberOfComments() {
        Task task = taskService.create(new Task(null, "Title", "desc", false));
        taskCommentService.add(task.id(), new TaskComment(null, null, "ana", "First", null));
        taskCommentService.add(task.id(), new TaskComment(null, null, "ana", "Second", null));
        taskCommentService.add(task.id(), new TaskComment(null, null, "ana", "Third", null));

        assertThat(taskCommentService.recent(2)).hasSize(2);
    }
}
