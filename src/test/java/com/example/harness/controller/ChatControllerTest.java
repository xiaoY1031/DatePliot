package com.example.harness.controller;

import com.example.harness.agent.AgentRuntime;
import com.example.harness.task.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    @Test
    void createsTaskWithoutChangingJsonResponseFormat() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.run(null, "帮我查看项目基本信息"))
                .thenReturn(new AgentRuntime.AgentResult("task-1", "项目基本信息"));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我查看项目基本信息\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Task-Id", "task-1"))
                .andExpect(jsonPath("$.answer").value("项目基本信息"));

        verify(runtime).run(null, "帮我查看项目基本信息");
    }

    @Test
    void continuesConversationUsingTaskHeader() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.run("task-1", "继续分析"))
                .thenReturn(new AgentRuntime.AgentResult("task-1", "继续回答"));
        MockMvc mockMvc = mockMvc(runtime);

        mockMvc.perform(post("/api/chat")
                        .header("X-Task-Id", "task-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"继续分析\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Task-Id", "task-1"))
                .andExpect(jsonPath("$.answer").value("继续回答"));
    }

    @Test
    void returnsBadRequestForUnknownTask() throws Exception {
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.run("missing", "继续"))
                .thenThrow(new TaskStore.UnknownTaskException("missing"));

        mockMvc(runtime).perform(post("/api/chat")
                        .header("X-Task-Id", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"继续\"}"))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mockMvc(AgentRuntime runtime) {
        return MockMvcBuilders.standaloneSetup(new ChatController(runtime)).build();
    }
}
