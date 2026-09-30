package com.codereview.app.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    void deleteReturnsNoContentWhenExisted() throws Exception {
        when(taskService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/tasks/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void searchReturnsMatchingTasks() throws Exception {
        when(taskService.search("milk", 0, 20)).thenReturn(List.of(new Task(1L, "Buy milk", "desc", false)));

        mockMvc.perform(get("/tasks/search").param("q", "milk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Buy milk"));
    }

    @Test
    void tagSummaryReturnsCountsPerTag() throws Exception {
        when(taskService.tagSummary()).thenReturn(Map.of("home", 2L));

        mockMvc.perform(get("/tasks/tags/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.home").value(2));
    }

    @Test
    void exportCsvReturnsOneRowPerTask() throws Exception {
        when(taskService.findAll()).thenReturn(List.of(new Task(1L, "Title", "desc", true, List.of("home", "urgent"))));

        mockMvc.perform(get("/tasks/export.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string("id,title,description,completed,tags\n1,Title,desc,true,home;urgent\n"));
    }

    @Test
    void addTagsReturnsUpdatedTask() throws Exception {
        when(taskService.addTags(eq(1L), any()))
                .thenReturn(Optional.of(new Task(1L, "Title", "desc", false, List.of("home"))));

        mockMvc.perform(post("/tasks/1/tags")
                        .contentType("application/json")
                        .content("[\"home\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0]").value("home"));
    }
}
