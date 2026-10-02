package com.codereview.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtValidator jwtValidator;

    @MockitoBean
    private LoginAttemptTracker loginAttemptTracker;

    @Test
    void loginWithValidCredentialsReturnsToken() throws Exception {
        when(jwtValidator.generateToken("admin")).thenReturn("fake-jwt-token");

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake-jwt-token"));

        verify(loginAttemptTracker).recordSuccess("admin");
    }

    @Test
    void loginWithInvalidCredentialsReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "wrong-password"))))
                .andExpect(status().isUnauthorized());

        verify(loginAttemptTracker).recordFailure("admin");
    }

    @Test
    void loginWhileLockedReturnsTooManyRequests() throws Exception {
        when(loginAttemptTracker.isLocked("admin")).thenReturn(true);

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "admin123"))))
                .andExpect(status().isTooManyRequests());

        verify(jwtValidator, never()).generateToken("admin");
    }

    @Test
    void refreshWithValidTokenReturnsNewToken() throws Exception {
        when(jwtValidator.extractUsername("old-token")).thenReturn(Optional.of("admin"));
        when(jwtValidator.generateToken("admin")).thenReturn("new-token");

        mockMvc.perform(post("/auth/refresh").header("Authorization", "Bearer old-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-token"));
    }

    @Test
    void refreshWithInvalidTokenReturnsUnauthorized() throws Exception {
        when(jwtValidator.extractUsername("bad-token")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/refresh").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
    }
}
