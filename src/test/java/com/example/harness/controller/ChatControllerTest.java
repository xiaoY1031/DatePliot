package com.example.harness.controller;

import com.example.harness.agent.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    @Test
    void keepsExistingRequestAndResponseFormat() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.run("帮我查看项目基本信息")).thenReturn("项目基本信息");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(runtime))
                .build();

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我查看项目基本信息\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("项目基本信息"));

        verify(runtime).run("帮我查看项目基本信息");
    }
}
