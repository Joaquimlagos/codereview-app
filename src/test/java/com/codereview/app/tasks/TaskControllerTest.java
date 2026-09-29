package com.codereview.app.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    @Test
    void getAllReturnsTasks() throws Exception {
        when(taskService.findAll()).thenReturn(List.of(new Task(1L, "Title", "desc", false)));

        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Title"));
    }

    @Test
    void getByIdReturnsNotFoundWhenMissing() throws Exception {
        when(taskService.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/tasks/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createReturnsCreatedTask() throws Exception {
        Task request = new Task(null, "New", "desc", false);
        when(taskService.create(any(Task.class))).thenReturn(new Task(1L, "New", "desc", false));

        mockMvc.perform(post("/tasks")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createAcceptsDueDate() throws Exception {
        Task request = new Task(null, "New", "desc", false, LocalDate.of(2026, 10, 15));
        when(taskService.create(any(Task.class)))
                .thenReturn(new Task(1L, "New", "desc", false, LocalDate.of(2026, 10, 15)));

        mockMvc.perform(post("/tasks")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value("2026-10-15"));
    }

    @Test
    void getOverdueReturnsOverdueTasks() throws Exception {
        when(taskService.findOverdue(any(LocalDate.class)))
                .thenReturn(List.of(new Task(1L, "Late", "desc", false, LocalDate.of(2026, 9, 1))));

        mockMvc.perform(get("/tasks/overdue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Late"));
    }

    @Test
    void bulkCompleteReturnsCompletedTasks() throws Exception {
        when(taskService.completeAll(List.of(1L, 2L))).thenReturn(List.of(
                new Task(1L, "First", "desc", true),
                new Task(2L, "Second", "desc", true)));

        mockMvc.perform(post("/tasks/bulk-complete")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of(1L, 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].completed").value(true));
    }

    @Test
    void getStatsReturnsStats() throws Exception {
        when(taskService.getStats(any(LocalDate.class))).thenReturn(new TaskStats(4, 2, 2, 1, 50));

        mockMvc.perform(get("/tasks/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.completionPercentage").value(50));
    }

    @Test
    void deleteReturnsNoContentWhenExisted() throws Exception {
        when(taskService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/tasks/1"))
                .andExpect(status().isNoContent());
    }
}
