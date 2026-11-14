package com.phh.procure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phh.procure.client.AgentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class TaskStreamApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentClient agentClient;

    private MockMvc mockMvc;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(agentClient.submit(anyString(), any()))
                .thenReturn(new AgentClient.AgentSubmitResponse("py-task-1", "RUNNING"));
        token = TestTokens.demoToken(mockMvc, objectMapper);
    }

    private String auth() {
        return "Bearer " + token;
    }

    private long createTask() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "SSE 测试需求 " + System.nanoTime());
        req.put("category", "办公用品");
        req.put("quantity", 10);
        req.put("budgetAmount", 1000);
        req.put("deliveryCity", "苏州");
        MvcResult created = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long requirementId = objectMapper.readTree(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("id").asLong();

        MvcResult task = mockMvc.perform(post("/api/requirements/" + requirementId + "/tasks")
                        .header("Authorization", auth()))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(task.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
    }

    private String ticket(long taskId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks/" + taskId + "/stream-ticket")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        return data.path("ticket").asText();
    }

    @Test
    @DisplayName("票据换 SSE 连接：回放历史阶段事件")
    void streamWithTicketReplaysEvents() throws Exception {
        long taskId = createTask();
        String ticket = ticket(taskId);

        MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId + "/stream").param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // SseEmitter 不会主动结束，不能 asyncDispatch（会一直等），直接读已写出的内容后关闭
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:stage").contains("PARSE");
        result.getRequest().getAsyncContext().complete();
    }

    @Test
    @DisplayName("缺少票据：401")
    void streamWithoutTicketRejected() throws Exception {
        long taskId = createTask();
        mockMvc.perform(get("/api/tasks/" + taskId + "/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("票据只能用一次：重放被拒")
    void ticketIsSingleUse() throws Exception {
        long taskId = createTask();
        String ticket = ticket(taskId);

        mockMvc.perform(get("/api/tasks/" + taskId + "/stream").param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        mockMvc.perform(get("/api/tasks/" + taskId + "/stream").param("ticket", ticket))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
