package com.codereview.app.tasks;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

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

    public List<Task> search(String query, int page, int size) {
        Pattern pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        List<Task> matches = tasks.values().stream()
                .filter(task -> matches(pattern, task.title()) || matches(pattern, task.description()))
                .sorted(Comparator.comparing(Task::id))
                .toList();
        int from = page * size;
        int to = Math.min(from + size, matches.size());
        return matches.subList(from, to);
    }

    public Map<String, Long> tagSummary() {
        Map<String, Long> summary = new TreeMap<>();
        for (Task task : tasks.values()) {
            for (String tag : task.tags()) {
                long count = tasks.values().stream()
                        .filter(other -> other.tags().contains(tag))
                        .count();
                summary.put(tag, count);
            }
        }
        return summary;
    }

    public Task create(Task task) {
        long id = nextId.getAndIncrement();
        Task created = new Task(id, task.title(), task.description(), task.completed(), task.tags());
        tasks.put(id, created);
        return created;
    }

    public Optional<Task> update(Long id, Task task) {
        if (!tasks.containsKey(id)) {
            return Optional.empty();
        }
        Task updated = new Task(id, task.title(), task.description(), task.completed(), task.tags());
        tasks.put(id, updated);
        return Optional.of(updated);
    }

    public Optional<Task> addTags(Long id, List<String> newTags) {
        Task current = tasks.get(id);
        if (current == null) {
            return Optional.empty();
        }
        Set<String> merged = new LinkedHashSet<>(current.tags());
        merged.addAll(newTags);
        Task updated = current.withTags(List.copyOf(merged));
        tasks.put(id, updated);
        return Optional.of(updated);
    }

    public boolean delete(Long id) {
        return tasks.remove(id) != null;
    }

    private static boolean matches(Pattern pattern, String text) {
        return text != null && pattern.matcher(text).find();
    }
}
