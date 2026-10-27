package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.integration.drawing.*;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Tag("integration")
@AutoConfigureMockMvc
class DrawingVerificationWorkspaceIntegrationTest extends BomMapperTestBase {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtUtils jwt;
  @Autowired ObjectMapper json;
  @Autowired DrawingVerificationService verifications;
  @Autowired ElectronicDrawingWorkflowContextPort contexts;
  @Autowired DrawingVerificationTaskMapper tasks;
  @Autowired DrawingVerificationResultMapper results;
  @Autowired DrawingVerificationCodec codec;
  @Autowired DrawingVerificationProperties properties;
  @MockBean CurrentU9BomGateway u9;
  @MockBean DrawingVerificationClient client;
  private String key, month, path;
  private Long itemId;
  private DrawingVerificationTask task;

  @BeforeEach
  void fixture() {
    key = "EDP07-" + UUID.randomUUID().toString().substring(0, 12);
    month = CostPricingPeriodUtils.currentPricingMonth();
    jdbc.update(
        "INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type)"
            + " VALUES(?,?,'COMMERCIAL')",
        key,
        month);
    Long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update(
        "INSERT INTO"
            + " oa_form_item(oa_form_id,material_no,sunl_model,product_name,customer_drawing,business_unit_type)"
            + " VALUES(?,?,?,'热力膨胀阀',?,'COMMERCIAL')",
        form,
        key,
        key,
        key + "-DRAW-MX");
    itemId =
        jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update(
        "INSERT INTO"
            + " lp_material_master_raw(material_code,organization_code,material_name,material_model,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','热力膨胀阀',?,?,1,'U9_API','EDP07')",
        key,
        key,
        key + "-DRAW-MX");
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    doAnswer(
            call -> {
              var payload = DrawingVerificationFixtures.initial("task-" + UUID.randomUUID());
              payload.put("differenceCount", 2);
              payload.putObject("data");
              return codec.parse(payload.toString());
            })
        .when(client)
        .verify(any());
    task =
        verifications.start(contexts.load(itemId, "COMMERCIAL", "210", month), null).orElseThrow();
    path = "/api/v1/quote-requests/" + key + "/items/" + itemId + "/drawing-verifications";
  }

  @Test
  void directDrawingModeBindsAnUnknownDrawingToTheQuoteAndReceivesItsFinalData() throws Exception {
    String suppliedDrawing = key + "-PROVIDER-TEST-MX";
    jdbc.update("UPDATE oa_form_item SET material_no=NULL,sunl_model=NULL,spec=NULL,customer_drawing=? WHERE id=?",
        suppliedDrawing, itemId);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_material_master_raw WHERE drawing_no=?", Integer.class, suppliedDrawing))
        .isZero();
    boolean previous = properties.isUseMaterialMaster();
    properties.setUseMaterialMaster(false);
    try {
      var started = read(admin(post(path).contentType(MediaType.APPLICATION_JSON)
          .content("{\"action\":\"START\"}"))).path("current");
      assertThat(started.path("drawingNo").asText()).isEqualTo(suppliedDrawing);
      assertThat(started.path("taskState").asText()).isEqualTo("WAIT_REVIEW");
      String externalTask = started.path("externalTaskId").asText();
      var confirmed = DrawingVerificationFixtures.confirmed(externalTask);
      var group = confirmed.putObject("data").putObject(suppliedDrawing);
      group.putNull("company");
      var row = group.putObject("params").putArray("items").addObject();
      for (String field : new String[] {"no", "code", "name", "qty", "mat", "grade", "hsf", "weight", "note"}) {
        row.put(field, "");
      }
      row.put("no", "1");
      row.put("qty", "3");
      for (int i = 0; i < 2; i++) {
        mvc.perform(post(DrawingReviewController.PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Review-Task", externalTask)
            .header("Idempotency-Key", externalTask)
            .content(confirmed.toString())).andExpect(status().isOk());
      }
      var current = read(admin(get(path))).path("current");
      assertThat(current.path("localTaskId").asLong()).isEqualTo(started.path("localTaskId").asLong());
      assertThat(current.path("taskState").asText()).isEqualTo("RECEIVED");
      assertThat(current.path("results")).hasSize(2);
      var detail = read(admin(get(path + "/results/" + current.path("currentResultId").asLong())));
      assertThat(detail.path("data").path(suppliedDrawing).path("params").path("items").get(0).path("qty").asText())
          .isEqualTo("3");
      assertThat(jdbc.queryForObject("SELECT material_no FROM oa_form_item WHERE id=?", String.class, itemId))
          .isNull();
      verify(client, times(2)).verify(any());
    } finally {
      properties.setUseMaterialMaster(previous);
    }
  }

  @Test
  void sharedDrawingIsOneChoiceAndCanBeStartedWithoutSettingAMaterialNumber() throws Exception {
    jdbc.update("UPDATE oa_form_item SET material_no=NULL WHERE id=?", itemId);
    jdbc.update(
        "INSERT INTO lp_material_master_raw(material_code,organization_code,material_name,"
            + "material_model,drawing_no,active_flag,source_type,import_batch_id)"
            + " VALUES(?,'COMMERCIAL','另一料号',?,?,1,'U9_API','EDP07_SHARED')",
        key + "-SECOND",
        key,
        key + "-DRAW-MX");
    var choices = read(admin(get(path))).path("drawingOptions");
    assertThat(choices).hasSize(1);
    assertThat(choices.get(0).path("drawingNo").asText()).isEqualTo(key + "-DRAW-MX");
    assertThat(choices.get(0).path("materialNo").isNull()).isTrue();
    for (int i = 0; i < 2; i++) {
      var state = read(admin(post(path).contentType(MediaType.APPLICATION_JSON)
          .content("{\"action\":\"START\"}")));
      assertThat(state.path("current").path("drawingNo").asText()).isEqualTo(key + "-DRAW-MX");
      assertThat(state.path("current").path("taskState").asText()).isEqualTo("WAIT_REVIEW");
    }
    assertThat(jdbc.queryForObject("SELECT material_no FROM oa_form_item WHERE id=?", String.class, itemId))
        .isNull();
    // 一次为原有 fixture，另一次为改成无料号后的新任务；重复点击不再调用。
    verify(client, times(2)).verify(argThat(query -> (key + "-DRAW-MX").equals(query.drawingNo())));
  }

  @Test
  void refreshAndHistoryReadsNeverPostOrWrite() throws Exception {
    var before =
        jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId());
    for (int i = 0; i < 3; i++) {
      var state = read(admin(get(path)));
      assertThat(state.path("current").path("localTaskId").asLong()).isEqualTo(task.getId());
      assertThat(state.path("current").path("externalTaskId").asText())
          .isEqualTo(task.getExternalTaskId());
      assertThat(state.path("current").path("taskState").asText()).isEqualTo("WAIT_REVIEW");
      assertThat(state.path("current").path("editUrl").asText()).isEqualTo(task.getEditUrl());
    }
    var detail = read(admin(get(path + "/results/" + task.getCurrentResultId())));
    assertThat(detail.path("adoption").asText()).isEqualTo("WAIT_REVIEW");
    assertThat(detail.path("plmData"))
        .isEqualTo(json.readTree(results.findById(task.getCurrentResultId()).getPlmDataJson()));
    assertThat(
            jdbc.queryForMap("SELECT * FROM lp_drawing_verification_task WHERE id=?", task.getId()))
        .isEqualTo(before);
    verify(client, times(1)).verify(any());
  }

  @Test
  void manualConfirmationUsesFinalDataWithAllOriginalPlmAndExtensions() throws Exception {
    var confirmed = DrawingVerificationFixtures.confirmed(task.getExternalTaskId());
    var group = confirmed.putObject("data").putObject(key + "-DRAW-MX");
    group.putNull("company");
    var items = group.putObject("params").putArray("items");
    var row = items.addObject();
    for (String field :
        new String[] {"no", "code", "name", "qty", "mat", "grade", "hsf", "weight", "note"})
      row.put(field, "");
    row.put("no", "001");
    row.put("qty", "0.1250");
    row.putNull("weight");
    confirmed
        .withObject("data")
        .putObject("ANOTHER-DRAW")
        .putNull("company")
        .putObject("params")
        .putArray("items");
    mvc.perform(
            post(DrawingReviewController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Review-Task", task.getExternalTaskId())
                .header("Idempotency-Key", task.getExternalTaskId())
                .content(confirmed.toString()))
        .andExpect(status().isOk());
    var current = read(admin(get(path))).path("current");
    assertThat(current.path("taskState").asText()).isEqualTo("RECEIVED");
    assertThat(current.path("differenceCount").asInt()).isEqualTo(2);
    var detail = read(admin(get(path + "/results/" + current.path("currentResultId").asLong())));
    assertThat(detail.path("adoption").asText()).isEqualTo("MANUAL_CONFIRMED");
    assertThat(detail.path("data")).isEqualTo(confirmed.path("data"));
    assertThat(detail.path("summary")).isEqualTo(confirmed);
    assertThat(detail.path("plmData"))
        .isEqualTo(json.readTree(results.findById(task.getCurrentResultId()).getPlmDataJson()));
    assertThat(detail.path("plmSourceResultId").asLong()).isEqualTo(task.getCurrentResultId());
    assertThat(current.path("results")).hasSize(2);
    verify(client, times(1)).verify(any());
  }

  @Test
  void restartRequiresExpectedLocalIdAndRepeatedClickOnlyCreatesOneTask() throws Exception {
    String body =
        json.writeValueAsString(Map.of("action", "RESTART", "expectedTaskId", task.getId()));
    var first = read(admin(post(path).contentType(MediaType.APPLICATION_JSON).content(body)));
    var second = read(admin(post(path).contentType(MediaType.APPLICATION_JSON).content(body)));
    assertThat(first.path("current").path("localTaskId"))
        .isEqualTo(second.path("current").path("localTaskId"));
    assertThat(first.path("current").path("localTaskId").asLong()).isNotEqualTo(task.getId());
    assertThat(second.path("history")).hasSize(2);
    assertThat(
            read(admin(get(path + "/results/" + task.getCurrentResultId())))
                .path("adoption")
                .asText())
        .isEqualTo("WAIT_REVIEW");
    verify(client, times(2)).verify(any());
  }

  @Test
  void otherQuotationProductMonthAndBusinessUnitCannotReadEvidence() throws Exception {
    mvc.perform(admin(get(path.replace(key, "WRONG-OA")))).andExpect(jsonPath("$.code").value(400));
    mvc.perform(admin(get(path.replace("/" + itemId + "/", "/999999999/"))))
        .andExpect(jsonPath("$.code").value(400));
    mvc.perform(
            get(path)
                .param("accountingMonth", month)
                .header("Authorization", "Bearer " + jwt.generateToken("admin", "HOUSEHOLD")))
        .andExpect(jsonPath("$.code").value(400));
    String previous = java.time.YearMonth.parse(month).minusMonths(1).toString();
    mvc.perform(
            get(path + "/results/" + task.getCurrentResultId())
                .param("accountingMonth", previous)
                .header("Authorization", "Bearer " + jwt.generateToken("admin", "COMMERCIAL")))
        .andExpect(jsonPath("$.code").value(400));
    verify(client, times(1)).verify(any());
  }

  @Test
  void missingInvalidMonthNeverFallsBackToCurrentMonth() throws Exception {
    mvc.perform(
            get(path).header("Authorization", "Bearer " + jwt.generateToken("admin", "COMMERCIAL")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get(path)
                .param("accountingMonth", "2026-13")
                .header("Authorization", "Bearer " + jwt.generateToken("admin", "COMMERCIAL")))
        .andExpect(jsonPath("$.code").value(400));
    verify(client, times(1)).verify(any());
  }

  @Test
  void anonymousAndUnprivilegedUserCannotReadOrOperate() throws Exception {
    mvc.perform(get(path).param("accountingMonth", month)).andExpect(status().isUnauthorized());
    jdbc.update(
        "INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_time,update_time)"
            + " VALUES(?,'无图库权限','unused','COMMERCIAL','0','0',NOW(),NOW())",
        key);
    String token = jwt.generateToken(key, "COMMERCIAL");
    for (var request :
        new MockHttpServletRequestBuilder[] {
          get(path),
          post(path).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\"}")
        }) {
      mvc.perform(
              request.param("accountingMonth", month).header("Authorization", "Bearer " + token))
          .andExpect(status().isForbidden());
    }
    verify(client, times(1)).verify(any());
  }

  @Test
  void startStillHonorsU9FirstAndClosedOaBeforeAnyNewPost() throws Exception {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.available("U9", "v1", "batch", 2));
    var response =
        read(
            admin(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"START\"}")));
    assertThat(response.path("notice").asText()).contains("U9 BOM 已可用");
    Long form =
        jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?", Long.class, itemId);
    jdbc.update(
        "INSERT INTO"
            + " lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version)"
            + " VALUES('EDP07','TEST',?,?,1)",
        key,
        form);
    jdbc.update(
        "INSERT INTO"
            + " lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json)"
            + " VALUES('EDP07','TEST',?,1,1,1,'CANCELLED','[]')",
        key);
    mvc.perform(
            admin(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"START\"}")))
        .andExpect(jsonPath("$.code").value(400));
    verify(client, times(1)).verify(any());
  }

  private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
    return request
        .param("accountingMonth", month)
        .header("Authorization", "Bearer " + jwt.generateToken("admin", "COMMERCIAL"));
  }

  private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
    var response =
        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
    return json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
  }
}
