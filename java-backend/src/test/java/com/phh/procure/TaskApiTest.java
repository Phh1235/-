package com.phh.procure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phh.procure.client.AgentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("接口：比价任务（创建/异步下发/取消/重试/事件回放）")
class TaskApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentClient agentClient;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        when(agentClient.submit(anyString(), any()))
                .thenReturn(new AgentClient.AgentSubmitResponse("py-task-1", "RUNNING"));
        token = TestTokens.demoToken(mockMvc, objectMapper);
    }

    private String auth() {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private long createRequirement() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "任务模块测试需求 " + System.nanoTime());
        body.put("category", "IT设备");
        body.put("spec", "14 寸商务笔记本 i5/16G/512G");
        body.put("quantity", 10);
        body.put("unit", "台");
        body.put("budgetAmount", 50000);
        body.put("currency", "CNY");
        body.put("deliveryCity", "苏州");
        MvcResult result = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return data(result).path("id").asLong();
    }

    private long createTask(long requirementId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/requirements/" + requirementId + "/tasks")
                        .header("Authorization", auth()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();
        return data(result).path("id").asLong();
    }

    private JsonNode awaitStatus(long taskId, String expected) throws Exception {
        for (int i = 0; i < 60; i++) {
            MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId).header("Authorization", auth()))
                    .andExpect(status().isOk()).andReturn();
            JsonNode task = data(result);
            if (expected.equals(task.path("status").asText())) {
                return task;
            }
            Thread.sleep(100);
        }
        fail("任务 " + taskId + " 未在 6s 内进入状态 " + expected);
        return null;
    }

    @Test
    @DisplayName("创建任务返回 202，异步下发后进入 RUNNING 并记录 externalTaskId 与阶段事件")
    void createDispatchesAsync() throws Exception {
        long requirementId = createRequirement();
        long taskId = createTask(requirementId);

        JsonNode task = awaitStatus(taskId, "RUNNING");
        // RUNNING 在调用 Python 之前就落库，外部任务号会晚几十毫秒回填，这里再轮询一次
        for (int i = 0; i < 40 && task.path("externalTaskId").asText().isEmpty(); i++) {
            Thread.sleep(50);
            MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId).header("Authorization", auth()))
                    .andExpect(status().isOk()).andReturn();
            task = data(result);
        }
        assertThat(task.path("externalTaskId").asText()).isEqualTo("py-task-1");
        assertThat(task.path("attempt").asInt()).isEqualTo(1);
        assertThat(task.path("mockMode").asBoolean()).isTrue();

        mockMvc.perform(get("/api/tasks/" + taskId + "/events").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data[0].seq").value(1));
    }

    @Test
    @DisplayName("需求单不存在时创建任务返回 404")
    void createOnMissingRequirement() throws Exception {
        mockMvc.perform(post("/api/requirements/999999/tasks").header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("同一需求单存在进行中任务时再次创建返回 409")
    void duplicateActiveTask() throws Exception {
        long requirementId = createRequirement();
        createTask(requirementId);
        mockMvc.perform(post("/api/requirements/" + requirementId + "/tasks").header("Authorization", auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("取消进行中任务成功，重复取消返回 409")
    void cancelThenConflict() throws Exception {
        long taskId = createTask(createRequirement());
        awaitStatus(taskId, "RUNNING");

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").header("Authorization", auth()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("已取消任务可重试，attempt 递增并重新下发")
    void retryCanceled() throws Exception {
        long taskId = createTask(createRequirement());
        awaitStatus(taskId, "RUNNING");
        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").header("Authorization", auth()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tasks/" + taskId + "/retry").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempt").value(2));

        JsonNode task = awaitStatus(taskId, "RUNNING");
        assertThat(task.path("attempt").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Python 不可达时任务落为 FAILED，错误信息可读")
    void dispatchFailureMarksFailed() throws Exception {
        when(agentClient.submit(anyString(), any())).thenThrow(new RuntimeException("Connection refused (127.0.0.1:8000)"));
        long taskId = createTask(createRequirement());

        JsonNode task = awaitStatus(taskId, "FAILED");
        assertThat(task.path("errorMessage").asText()).contains("调用 Python Agent 失败");
        assertThat(task.path("finishedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("需求单已有任务时禁止删除需求单（保留审计链路）")
    void deleteRequirementWithTask() throws Exception {
        long requirementId = createRequirement();
        createTask(requirementId);
        mockMvc.perform(delete("/api/requirements/" + requirementId).header("Authorization", auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("按状态过滤任务列表")
    void listByStatus() throws Exception {
        long taskId = createTask(createRequirement());
        awaitStatus(taskId, "RUNNING");

        mockMvc.perform(get("/api/tasks").param("status", "RUNNING").param("size", "100")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("RUNNING"));
    }
}
