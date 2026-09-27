package com.codereview.app.tasks;

import org.springframework.stereotype.Service;

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

    public Task create(Task task) {
        long id = nextId.getAndIncrement();
        Task created = new Task(id, task.title(), task.description(), task.completed());
        tasks.put(id, created);
        return created;
    }

    public Optional<Task> update(Long id, Task task) {
        if (!tasks.containsKey(id)) {
            return Optional.empty();
        }
        Task updated = new Task(id, task.title(), task.description(), task.completed());
        tasks.put(id, updated);
        return Optional.of(updated);
    }

    public boolean delete(Long id) {
        return tasks.remove(id) != null;
    }
}
