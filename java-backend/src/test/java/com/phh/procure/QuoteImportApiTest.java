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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("接口：报价单导入（模板/预览/入库/去重/撤销/任务来源）")
class QuoteImportApiTest {

    private static final String HEADER =
            "供应商名称,统一社会信用代码,品类,规格,含税单价,币种,单位,起订量,交期(天),是否含税,有效期至,联系人,电话,报价日期,备注\n";

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
                .thenReturn(new AgentClient.AgentSubmitResponse("py-task-import", "RUNNING"));
        token = TestTokens.demoToken(mockMvc, objectMapper);
    }

    private String auth() {
        return "Bearer " + token;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    /** 按固定 15 列拼一行，避免手工数逗号数错列（这正是导入功能最容易出的 bug）。 */
    private static String row(String... cells) {
        String[] full = new String[15];
        for (int i = 0; i < 15; i++) {
            full[i] = i < cells.length && cells[i] != null ? cells[i] : "";
        }
        return String.join(",", full) + "\n";
    }

    @Test
    @DisplayName("下载模板：返回 CSV，含必填说明与完整表头")
    void downloadTemplate() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/quotes/template.csv").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("quote-template.csv")))
                .andReturn();
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("必填列").contains("供应商名称").contains("含税单价").contains("报价日期");
        assertThat(text).contains("选填列");
    }

    @Test
    @DisplayName("预览：逐行校验，缺必填/金额非法/日期格式错误都给出可读原因")
    void previewValidatesRows() throws Exception {
        String content = HEADER
                + row("预览甲公司", "", "导入预览品类", "\"A4, 80g\"", "¥28.50", "CNY", "件", "10", "5", "是",
                "2026-12-31", "张三", "13800001111", "2026-11-20", "")
                + row("预览乙公司", "", "导入预览品类", "A4纸", "29.9", "", "", "0", "0", "", "", "", "", "2026-11-20", "正常行")
                + row("预览丙公司", "", "导入预览品类", "A4纸", "", "CNY", "件", "", "", "", "", "", "", "2026-11-20", "缺价格")
                + row("预览丁公司", "", "导入预览品类", "A4纸", "abc", "CNY", "件", "", "", "", "", "", "", "不是日期", "金额与日期都错");
        MvcResult result = mockMvc.perform(multipart("/api/quotes/import/preview")
                        .file(csv("preview.csv", content))
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.okCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(2))
                .andReturn();
        JsonNode rows = data(result).path("rows");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(1).path("ok").asBoolean()).isTrue();
        assertThat(rows.get(2).path("errors").toString()).contains("含税单价必填");
        assertThat(rows.get(3).path("errors").toString()).contains("报价日期格式不对");
    }

    @Test
    @DisplayName("入库：写报价单并自动创建供应商，重复导入按唯一键跳过")
    void commitCreatesSuppliersAndDedupes() throws Exception {
        String content = HEADER
                + "导入甲公司,,导入入库品类,\"A4, 80g\",¥28.50,CNY,件,10,5,是,2026-12-31,张三,13800001111,2026-11-20,\n"
                + "导入乙公司,,导入入库品类,A4纸,31.20,CNY,件,20,7,否,2026-12-31,李四,13900002222,2026-11-20,\n";

        MvcResult first = mockMvc.perform(multipart("/api/quotes/import")
                        .file(csv("quotes.csv", content))
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(2))
                .andExpect(jsonPath("$.data.duplicated").value(0))
                .andReturn();
        long batchId = data(first).path("batchId").asLong();

        mockMvc.perform(get("/api/quotes").param("category", "导入入库品类").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(28.5))
                .andExpect(jsonPath("$.data.items[0].verified").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].sourceRef").value("quotes.csv#L2"));

        // 同一份文件再导一次：不是报错，而是"识别为重复"
        mockMvc.perform(multipart("/api/quotes/import")
                        .file(csv("quotes.csv", content))
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(0))
                .andExpect(jsonPath("$.data.duplicated").value(2));

        // 撤销整批：报价消失
        mockMvc.perform(delete("/api/quotes/import/batches/" + batchId).header("Authorization", auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/quotes").param("category", "导入入库品类").header("Authorization", auth()))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("安全：电话入库前脱敏")
    void phoneIsMasked() throws Exception {
        String content = HEADER + "脱敏公司,,脱敏品类,A4纸,10,CNY,件,1,3,是,,,13812345678,2026-11-20,\n";
        mockMvc.perform(multipart("/api/quotes/import")
                        .file(csv("mask.csv", content))
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(1));
        mockMvc.perform(get("/api/quotes").param("category", "脱敏品类").header("Authorization", auth()))
                .andExpect(jsonPath("$.data.items[0].contactPhone").value("138****5678"));
    }

    @Test
    @DisplayName("非模板文件：缺必填列直接 400，提示缺哪几列")
    void rejectWrongHeader() throws Exception {
        mockMvc.perform(multipart("/api/quotes/import/preview")
                        .file(csv("bad.csv", "公司,价格\n甲公司,10\n"))
                        .header("Authorization", auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺少必填列")));
    }

    @Test
    @DisplayName("任务：quoteSource=IMPORT 时按导入报价建任务，无报价则快速失败")
    void taskUsesImportedQuotes() throws Exception {
        String content = HEADER + "任务甲公司,,导入任务品类,A4纸,20.00,CNY,件,1,3,是,,,13900003333,2026-11-20,\n";
        mockMvc.perform(multipart("/api/quotes/import")
                        .file(csv("task.csv", content))
                        .header("Authorization", auth()))
                .andExpect(status().isOk());

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "导入报价比价 " + System.nanoTime());
        req.put("category", "导入任务品类");
        req.put("spec", "A4纸");
        req.put("quantity", 100);
        req.put("unit", "件");
        req.put("budgetAmount", 5000);
        req.put("currency", "CNY");
        req.put("deliveryCity", "苏州");
        MvcResult created = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        long reqId = data(created).path("id").asLong();

        mockMvc.perform(post("/api/requirements/" + reqId + "/tasks")
                        .param("quoteSource", "IMPORT")
                        .header("Authorization", auth()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.quoteSource").value("IMPORT"))
                .andExpect(jsonPath("$.data.mockMode").value(false));

        // 不存在的品类：任务应快速失败并给出可操作提示，而不是一直 RUNNING
        Map<String, Object> req2 = new LinkedHashMap<>(req);
        req2.put("category", "没人报过价的品类");
        MvcResult created2 = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();
        long reqId2 = data(created2).path("id").asLong();
        MvcResult task2 = mockMvc.perform(post("/api/requirements/" + reqId2 + "/tasks")
                        .param("quoteSource", "IMPORT")
                        .header("Authorization", auth()))
                .andExpect(status().isAccepted())
                .andReturn();
        long taskId2 = data(task2).path("id").asLong();

        String status = "";
        String message = "";
        for (int i = 0; i < 40; i++) {
            MvcResult r = mockMvc.perform(get("/api/tasks/" + taskId2).header("Authorization", auth()))
                    .andExpect(status().isOk()).andReturn();
            JsonNode task = data(r);
            status = task.path("status").asText();
            message = task.path("errorMessage").asText("");
            if ("FAILED".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("FAILED");
        assertThat(message).contains("还没有导入报价单");
    }

    @Test
    @DisplayName("校验：quoteSource 传非法值返回 400")
    void rejectUnknownQuoteSource() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", "非法来源 " + System.nanoTime());
        req.put("category", "办公用品");
        req.put("spec", "A4");
        req.put("quantity", 1);
        req.put("budgetAmount", 100);
        MvcResult created = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated()).andReturn();
        long reqId = data(created).path("id").asLong();
        mockMvc.perform(post("/api/requirements/" + reqId + "/tasks")
                        .param("quoteSource", "SCRAPE")
                        .header("Authorization", auth()))
                .andExpect(status().isBadRequest());
    }
}
