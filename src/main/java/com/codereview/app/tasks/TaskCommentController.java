package com.codereview.app.tasks;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tasks")
public class TaskCommentController {

    private static final int MAX_COMMENT_LENGTH = 500;

    private final TaskCommentService taskCommentService;

    public TaskCommentController(TaskCommentService taskCommentService) {
        this.taskCommentService = taskCommentService;
    }

    @GetMapping("/{taskId}/comments")
    public List<TaskComment> getByTask(@PathVariable Long taskId) {
        return taskCommentService.findByTask(taskId);
    }

    @GetMapping("/comments/recent")
    public List<TaskComment> recent(@RequestParam(defaultValue = "10") int limit) {
        return taskCommentService.recent(limit);
    }

    @PostMapping("/{taskId}/comments")
    public ResponseEntity<TaskComment> add(@PathVariable Long taskId, @RequestBody TaskComment comment) {
        if (comment.text().isBlank() || comment.text().length() > MAX_COMMENT_LENGTH) {
            return ResponseEntity.badRequest().build();
        }
        TaskComment created = taskCommentService.add(taskId, comment);
        if (created == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{taskId}/comments/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable Long taskId, @PathVariable Long commentId) {
        return taskCommentService.delete(taskId, commentId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
