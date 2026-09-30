package com.codereview.app.tasks;

import java.util.List;
import java.util.Objects;

final class TaskCsv {

    private static final String HEADER = "id,title,description,completed,tags";

    private TaskCsv() {
    }

    static String write(List<Task> tasks) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (Task task : tasks) {
            csv.append(task.id()).append(',')
                    .append(Objects.toString(task.title(), "")).append(',')
                    .append(Objects.toString(task.description(), "")).append(',')
                    .append(task.completed()).append(',')
                    .append(String.join(";", task.tags()))
                    .append('\n');
        }
        return csv.toString();
    }
}
