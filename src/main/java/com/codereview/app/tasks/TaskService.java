package com.codereview.app.tasks;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskService {

    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public List<Task> findAll() {
        return List.copyOf(tasks.values());
    }

    public Optional<Task> findById(Long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public List<Task> findOverdue(LocalDate today) {
        return tasks.values().stream()
                .filter(task -> !task.completed() && !task.dueDate().isAfter(today))
                .toList();
    }

    public Task create(Task task) {
        long id = nextId.getAndIncrement();
        Task created = new Task(id, task.title(), task.description(), task.completed(), task.dueDate());
        tasks.put(id, created);
        return created;
    }

    public Optional<Task> update(Long id, Task task) {
        if (!tasks.containsKey(id)) {
            return Optional.empty();
        }
        Task updated = new Task(id, task.title(), task.description(), task.completed(), task.dueDate());
        tasks.put(id, updated);
        return Optional.of(updated);
    }

    public List<Task> completeAll(List<Long> ids) {
        List<Task> completed = new ArrayList<>();
        for (Long id : ids) {
            Task updated = tasks.computeIfPresent(id,
                    (key, task) -> new Task(key, task.title(), task.description(), true, task.dueDate()));
            if (updated != null) {
                completed.add(updated);
            }
        }
        return completed;
    }

    public TaskStats getStats(LocalDate today) {
        long total = tasks.size();
        long completed = tasks.values().stream().filter(Task::completed).count();
        long pending = tasks.values().stream().filter(task -> !task.completed()).count();
        long overdue = tasks.values().stream()
                .filter(task -> !task.completed() && task.dueDate() != null && task.dueDate().isBefore(today))
                .count();
        long completionPercentage = completed * 100 / total;
        return new TaskStats(total, completed, pending, overdue, completionPercentage);
    }

    public boolean delete(Long id) {
        return tasks.remove(id) != null;
    }
}
