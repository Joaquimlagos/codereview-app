package com.codereview.app.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskCommentController.class)
class TaskCommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskCommentService taskCommentService;

    @Test
    void getByTaskReturnsComments() throws Exception {
        when(taskCommentService.findByTask(1L))
                .thenReturn(List.of(new TaskComment(1L, 1L, "ana", "First", Instant.now())));

        mockMvc.perform(get("/tasks/1/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("First"));
    }

    @Test
    void addReturnsCreatedComment() throws Exception {
        TaskComment request = new TaskComment(null, null, "ana", "First", null);
        when(taskCommentService.add(eq(1L), any(TaskComment.class)))
                .thenReturn(new TaskComment(1L, 1L, "ana", "First", Instant.now()));

        mockMvc.perform(post("/tasks/1/comments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void addReturnsNotFoundWhenTaskIsMissing() throws Exception {
        TaskComment request = new TaskComment(null, null, "ana", "First", null);
        when(taskCommentService.add(eq(99L), any(TaskComment.class))).thenReturn(null);

        mockMvc.perform(post("/tasks/99/comments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void addReturnsBadRequestWhenTextIsBlank() throws Exception {
        TaskComment request = new TaskComment(null, null, "ana", "   ", null);

        mockMvc.perform(post("/tasks/1/comments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturnsNoContentWhenExisted() throws Exception {
        when(taskCommentService.delete(1L, 2L)).thenReturn(true);

        mockMvc.perform(delete("/tasks/1/comments/2"))
                .andExpect(status().isNoContent());
    }
}
