package com.phh.procure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("接口：采购需求单 CRUD")
class RequirementApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = TestTokens.demoToken(mockMvc, objectMapper);
    }

    private String auth() {
        return "Bearer " + token;
    }

    private Map<String, Object> body(String title) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("category", "办公用品");
        map.put("spec", "A4 复印纸 70g，500 张/包");
        map.put("quantity", 200);
        map.put("unit", "包");
        map.put("budgetAmount", 6000);
        map.put("currency", "CNY");
        map.put("deliveryCity", "苏州");
        map.put("deadline", "2026-12-31");
        map.put("description", "用于行政部日常打印");
        return map;
    }

    private long create(String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(title))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("id").asLong()).isPositive();
        return data.path("id").asLong();
    }

    @Test
    @DisplayName("创建后按 ID 查回，默认单位/币种生效")
    void createThenDetail() throws Exception {
        long id = create("办公用品季度采购");

        mockMvc.perform(get("/api/requirements/" + id).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("办公用品季度采购"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.unit").value("包"))
                .andExpect(jsonPath("$.data.createdBy").isNumber());
    }

    @Test
    @DisplayName("列表包含刚创建的需求单")
    void listContainsCreated() throws Exception {
        long id = create("列表可见性验证");
        MvcResult result = mockMvc.perform(get("/api/requirements").param("size", "100").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("items");
        boolean found = false;
        for (JsonNode item : items) {
            if (item.path("id").asLong() == id) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("更新后详情生效")
    void update() throws Exception {
        long id = create("待修改需求");
        Map<String, Object> update = body("已修改需求");
        update.put("quantity", 300);

        mockMvc.perform(put("/api/requirements/" + id).header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已修改需求"))
                .andExpect(jsonPath("$.data.quantity").value(300));
    }

    @Test
    @DisplayName("删除后查询返回 404")
    void deleteThenGone() throws Exception {
        long id = create("待删除需求");
        mockMvc.perform(delete("/api/requirements/" + id).header("Authorization", auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/requirements/" + id).header("Authorization", auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("参数校验：标题为空返回 400")
    void validation() throws Exception {
        mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("币种格式非法返回 400")
    void currencyValidation() throws Exception {
        Map<String, Object> invalid = body("币种校验");
        invalid.put("currency", "rmb");
        mockMvc.perform(post("/api/requirements").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("不存在的需求单返回 404")
    void notFound() throws Exception {
        mockMvc.perform(get("/api/requirements/999999").header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }
}
