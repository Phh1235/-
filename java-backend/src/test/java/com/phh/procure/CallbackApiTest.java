package com.phh.procure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phh.procure.client.AgentClient;
import com.phh.procure.client.CallbackSigner;
import com.phh.procure.config.AppProperties;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class CallbackApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppProperties props;

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

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private long createTask() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "回调测试需求 " + System.nanoTime());
        req.put("category", "办公用品");
        req.put("spec", "A4 复印纸 70g");
        req.put("quantity", 100);
        req.put("budgetAmount", 5000);
        req.put("deliveryCity", "苏州");
        MvcResult created = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long requirementId = data(created).path("id").asLong();

        MvcResult task = mockMvc.perform(post("/api/requirements/" + requirementId + "/tasks")
                        .header("Authorization", auth()))
                .andExpect(status().isAccepted())
                .andReturn();
        return data(task).path("id").asLong();
    }

    private void awaitStatus(long taskId, String expected) throws Exception {
        String last = "";
        for (int i = 0; i < 60; i++) {
            MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId).header("Authorization", auth()))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode node = data(result);
            last = node.path("status").asText() + "/" + node.path("errorMessage").asText("");
            if (expected.equals(node.path("status").asText())) {
                return;
            }
            if ("FAILED".equals(node.path("status").asText())) {
                fail("任务进入 FAILED：" + node.path("errorMessage").asText());
            }
            Thread.sleep(100);
        }
        fail("任务未在预期时间内进入状态 " + expected + "，当前 " + last);
    }

    private Map<String, Object> quote(long supplierId, String name, double price, String sourceUrl, boolean verified) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("supplierId", supplierId);
        q.put("supplierName", name);
        q.put("unitPrice", price);
        q.put("currency", "CNY");
        q.put("unit", "包");
        q.put("moq", 100);
        q.put("leadTimeDays", 7);
        q.put("taxIncluded", true);
        q.put("sourceUrl", sourceUrl);
        q.put("fetchedAt", "2026-11-14T07:00:00");
        q.put("confidence", 0.92);
        q.put("verified", verified);
        q.put("rawSnippet", name + " 含税单价 " + price + " 元");
        return q;
    }

    private Map<String, Object> payload(long taskId, String eventId, String eventType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("taskId", taskId);
        body.put("eventType", eventType);
        return body;
    }

    private ResultActions postSigned(String json, String timestamp, String nonce) throws Exception {
        String signature = CallbackSigner.sign(props.getAgent().getCallbackSecret(), timestamp, nonce, json);
        return mockMvc.perform(post("/internal/agent/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("X-Agent-Timestamp", timestamp)
                .header("X-Agent-Nonce", nonce)
                .header("X-Agent-Signature", signature));
    }

    private ResultActions postCallback(String json) throws Exception {
        return postSigned(json, String.valueOf(Instant.now().getEpochSecond()), UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("合法签名的成功回调：落库报价/风险/结果，结果接口与导出可用")
    void successCallbackPersistsResult() throws Exception {
        long taskId = createTask();
        awaitStatus(taskId, "RUNNING");

        Map<String, Object> stage = payload(taskId, "evt-stage-1", "STAGE");
        stage.put("stage", "FETCH");
        stage.put("progress", 40);
        stage.put("message", "抓取到 2 条报价");
        postCallback(objectMapper.writeValueAsString(stage)).andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + taskId).header("Authorization", auth()))
                .andExpect(jsonPath("$.data.stage").value("FETCH"))
                .andExpect(jsonPath("$.data.progress").value(40));

        List<Map<String, Object>> quotes = new ArrayList<>();
        quotes.add(quote(1L, "华东金属材料有限公司", 12.5,
                "https://example.com/suppliers/sup-001", true));
        quotes.add(quote(3L, "苏州某包装厂", 11.8, null, false));

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("supplierName", "苏州某包装厂");
        risk.put("type", "QUALIFICATION");
        risk.put("level", "HIGH");
        risk.put("title", "营业执照信息缺失");
        risk.put("detail", "页面未披露统一社会信用代码");
        risk.put("sourceUrl", null);

        Map<String, Object> resultBody = new LinkedHashMap<>();
        resultBody.put("recommendedSupplier", "苏州某包装厂");
        resultBody.put("recommendation", "单价最低但资质待核实，建议人工复核后下单");
        resultBody.put("totalCandidates", 2);
        resultBody.put("summaryJson", "{\"quotes\":2}");
        resultBody.put("reportMd", "# 比价报告\n\n推荐：苏州某包装厂（11.80 元/包）");

        Map<String, Object> done = payload(taskId, "evt-done-1", "SUCCEEDED");
        done.put("stage", "REPORT");
        done.put("progress", 100);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quotes", quotes);
        payload.put("risks", List.of(risk));
        payload.put("result", resultBody);
        done.put("payload", payload);

        postCallback(objectMapper.writeValueAsString(done)).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId + "/result").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.task.stage").value("DONE"))
                .andExpect(jsonPath("$.data.task.progress").value(100))
                .andExpect(jsonPath("$.data.quotes.length()").value(2))
                // 单价升序
                .andExpect(jsonPath("$.data.quotes[0].supplierName").value("苏州某包装厂"))
                // 无来源 URL 的报价必须待人工核实
                .andExpect(jsonPath("$.data.quotes[0].needsReview").value(true))
                .andExpect(jsonPath("$.data.quotes[1].needsReview").value(false))
                .andExpect(jsonPath("$.data.risks[0].needsReview").value(true))
                .andExpect(jsonPath("$.data.result.recommendedSupplier").value("苏州某包装厂"))
                .andReturn();

        assertThat(data(result).path("quotes").get(0).path("verified").asBoolean()).isFalse();

        MvcResult csv = mockMvc.perform(get("/api/tasks/" + taskId + "/export.csv").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();
        String csvText = new String(csv.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csvText).contains("供应商").contains("华东金属材料有限公司").contains("苏州某包装厂");

        mockMvc.perform(get("/api/tasks/" + taskId + "/report.md").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.containsString("比价报告")));
    }

    @Test
    @DisplayName("签名错误：401，且不改动任务")
    void invalidSignatureRejected() throws Exception {
        long taskId = createTask();
        String json = objectMapper.writeValueAsString(payload(taskId, "evt-bad", "STAGE"));
        String ts = String.valueOf(Instant.now().getEpochSecond());
        mockMvc.perform(post("/internal/agent/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("X-Agent-Timestamp", ts)
                        .header("X-Agent-Nonce", UUID.randomUUID().toString())
                        .header("X-Agent-Signature", "deadbeef"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(get("/api/tasks/" + taskId).header("Authorization", auth()))
                .andExpect(jsonPath("$.data.stage").value("PARSE"));
    }

    @Test
    @DisplayName("时间戳超出 ±5 分钟：拒绝")
    void staleTimestampRejected() throws Exception {
        long taskId = createTask();
        String json = objectMapper.writeValueAsString(payload(taskId, "evt-stale", "STAGE"));
        String old = String.valueOf(Instant.now().getEpochSecond() - 600);
        postSigned(json, old, UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("nonce 重放：第二次拒绝")
    void replayedNonceRejected() throws Exception {
        long taskId = createTask();
        String nonce = UUID.randomUUID().toString();
        String ts = String.valueOf(Instant.now().getEpochSecond());

        postSigned(objectMapper.writeValueAsString(payload(taskId, "evt-n-1", "STAGE")), ts, nonce)
                .andExpect(status().isOk());
        postSigned(objectMapper.writeValueAsString(payload(taskId, "evt-n-2", "STAGE")), ts, nonce)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("同一 eventId 重复投递：幂等，不重复落库")
    void duplicateEventIdIdempotent() throws Exception {
        long taskId = createTask();
        awaitStatus(taskId, "RUNNING");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quotes", List.of(quote(2L, "重复投递测试供应商", 9.9,
                "https://example.com/suppliers/sup-002", true)));
        Map<String, Object> done = payload(taskId, "evt-dup", "SUCCEEDED");
        done.put("stage", "REPORT");
        done.put("progress", 100);
        done.put("payload", body);
        String json = objectMapper.writeValueAsString(done);

        postCallback(json).andExpect(status().isOk());
        postCallback(json).andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + taskId + "/result").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotes.length()").value(1));
    }

    @Test
    @DisplayName("任务已取消后再来成功回调：丢弃，不覆盖终态")
    void successCallbackOnCanceledTaskIgnored() throws Exception {
        long taskId = createTask();
        awaitStatus(taskId, "RUNNING");

        mockMvc.perform(post("/api/tasks/" + taskId + "/cancel").header("Authorization", auth()))
                .andExpect(status().isOk());

        Map<String, Object> done = payload(taskId, "evt-late", "SUCCEEDED");
        done.put("stage", "REPORT");
        done.put("progress", 100);
        done.put("payload", Map.of("quotes", List.of(quote(4L, "迟到供应商", 8.8,
                "https://example.com/suppliers/sup-004", true))));
        postCallback(objectMapper.writeValueAsString(done)).andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/" + taskId + "/result").header("Authorization", auth()))
                .andExpect(jsonPath("$.data.task.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.quotes.length()").value(0));
    }
}
