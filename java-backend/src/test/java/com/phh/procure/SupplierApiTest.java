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
@DisplayName("接口：供应商主数据 CRUD")
class SupplierApiTest {

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

    private Map<String, Object> body(String code, String name, String category) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("name", name);
        map.put("category", category);
        map.put("region", "苏州");
        map.put("website", "https://example.com/suppliers/" + code.toLowerCase());
        map.put("contactName", "测试联系人");
        map.put("contactPhone", "138****9999");
        map.put("qualifications", "ISO9001");
        map.put("rating", 4.5);
        map.put("blacklisted", false);
        map.put("note", "接口测试创建");
        return map;
    }

    @Test
    @DisplayName("列表分页返回种子数据")
    void listSeeded() throws Exception {
        mockMvc.perform(get("/api/suppliers").param("page", "1").param("size", "20").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.data.items[0].code").isNotEmpty());
    }

    @Test
    @DisplayName("按品类过滤，结果品类全部命中")
    void filterByCategory() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/suppliers")
                        .param("category", "IT设备").param("size", "50").header("Authorization", auth()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("items");
        assertThat(items.size()).isGreaterThan(0);
        items.forEach(item -> assertThat(item.path("category").asText()).isEqualTo("IT设备"));
    }

    @Test
    @DisplayName("新建后可按 ID 查回，字段与入参一致")
    void createThenDetail() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/suppliers")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("SUP-T01", "测试供应商甲", "办公用品"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.code").value("SUP-T01"))
                .andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/suppliers/" + id).header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("测试供应商甲"))
                .andExpect(jsonPath("$.data.blacklisted").value(false));
    }

    @Test
    @DisplayName("编码重复返回 409")
    void duplicateCode() throws Exception {
        String payload = objectMapper.writeValueAsString(body("SUP-T02", "测试供应商乙", "办公用品"));
        mockMvc.perform(post("/api/suppliers").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/suppliers").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("更新后详情生效")
    void update() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/suppliers").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("SUP-T03", "测试供应商丙", "办公用品"))))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("id").asLong();

        mockMvc.perform(put("/api/suppliers/" + id).header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("SUP-T03", "测试供应商丙（已改名）", "劳保用品"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("测试供应商丙（已改名）"));

        mockMvc.perform(get("/api/suppliers/" + id).header("Authorization", auth()))
                .andExpect(jsonPath("$.data.category").value("劳保用品"));
    }

    @Test
    @DisplayName("删除后查询返回 404")
    void deleteThenGone() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/suppliers").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("SUP-T04", "测试供应商丁", "包装材料"))))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("id").asLong();

        mockMvc.perform(delete("/api/suppliers/" + id).header("Authorization", auth()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/suppliers/" + id).header("Authorization", auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("参数校验：名称为空返回 400")
    void validation() throws Exception {
        mockMvc.perform(post("/api/suppliers").header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("SUP-T05", "", "办公用品"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("未登录访问返回 401")
    void unauthorized() throws Exception {
        mockMvc.perform(get("/api/suppliers")).andExpect(status().isUnauthorized());
    }
}
