package com.codereview.app.projects;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void getAllHidesArchivedProjectsByDefault() throws Exception {
        when(projectService.findAll(false)).thenReturn(List.of(new Project(1L, "Apollo", "moon", false)));

        mockMvc.perform(get("/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Apollo"))
                .andExpect(jsonPath("$[0].archived").value(false));
    }

    @Test
    void getAllPassesTheIncludeArchivedFlagThrough() throws Exception {
        when(projectService.findAll(true)).thenReturn(List.of(new Project(1L, "Old", null, true)));

        mockMvc.perform(get("/projects").param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].archived").value(true));
    }

    @Test
    void getByIdReturnsNotFoundWhenMissing() throws Exception {
        when(projectService.findById(404L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/projects/404")).andExpect(status().isNotFound());
    }

    @Test
    void createReturnsCreatedProject() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("Apollo", "moon");
        when(projectService.create(any(CreateProjectRequest.class)))
                .thenReturn(new Project(1L, "Apollo", "moon", false));

        mockMvc.perform(post("/projects")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createReturnsBadRequestWhenTheServiceRejectsTheProject() throws Exception {
        when(projectService.create(any(CreateProjectRequest.class)))
                .thenThrow(new InvalidProjectException("name must not be blank"));

        mockMvc.perform(post("/projects")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateProjectRequest(" ", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturnsNotFoundWhenMissing() throws Exception {
        when(projectService.update(eq(404L), any(UpdateProjectRequest.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(put("/projects/404")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new UpdateProjectRequest("Apollo", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveReturnsTheArchivedProject() throws Exception {
        when(projectService.archive(1L)).thenReturn(Optional.of(new Project(1L, "Apollo", null, true)));

        mockMvc.perform(post("/projects/1/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
    }

    @Test
    void unarchiveReturnsTheLiveProject() throws Exception {
        when(projectService.unarchive(1L)).thenReturn(Optional.of(new Project(1L, "Apollo", null, false)));

        mockMvc.perform(post("/projects/1/unarchive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void deleteReturnsNoContentWhenRemoved() throws Exception {
        when(projectService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/projects/1")).andExpect(status().isNoContent());
    }

    @Test
    void deleteReturnsBadRequestWhenTheProjectIsStillLive() throws Exception {
        when(projectService.delete(1L))
                .thenThrow(new InvalidProjectException("project must be archived before it can be deleted"));

        mockMvc.perform(delete("/projects/1")).andExpect(status().isBadRequest());
    }
}
