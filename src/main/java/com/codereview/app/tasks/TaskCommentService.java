package com.codereview.app.tasks;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskCommentService {

    private final Map<Long, List<TaskComment>> commentsByTask = new ConcurrentHashMap<>();
    private final TaskService taskService;
    private long nextId = 1;

    public TaskCommentService(TaskService taskService) {
        this.taskService = taskService;
    }

    public List<TaskComment> findByTask(Long taskId) {
        return commentsByTask.getOrDefault(taskId, new ArrayList<>());
    }

    public TaskComment add(Long taskId, TaskComment comment) {
        if (taskService.findById(taskId).isEmpty()) {
            return null;
        }
        TaskComment created = new TaskComment(nextId++, taskId, comment.author(), comment.text(), Instant.now());
        commentsByTask.computeIfAbsent(taskId, id -> new ArrayList<>()).add(created);
        return created;
    }

    public boolean delete(Long taskId, Long commentId) {
        return commentsByTask.get(taskId).removeIf(comment -> comment.id().equals(commentId));
    }

    public List<TaskComment> recent(int limit) {
        return commentsByTask.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(TaskComment::createdAt).reversed())
                .limit(limit)
                .toList();
    }
}
