package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration")
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "integration.oa.mode=MOCK",
      "integration.oa.environment=I01_TEST",
          "integration.oa.clients.test.source-system=WEAVER",
      "integration.oa.clients.test.environment=I01_TEST",
      "integration.oa.clients.test.mode=MOCK",
      "integration.oa.clients.test.business-units=COMMERCIAL",
      "integration.oa.clients.test.secret=i01-test-only-token-at-least-32-characters"
    })
class OaQuotationIntegrationTest extends BomMapperTestBase {
  static final String URL = "/open-api/v1/oa/quotation-requests";
  static final String TOKEN = "Bearer i01-test-only-token-at-least-32-characters";
  static final OaPeer PEER = new OaPeer("WEAVER", "I01_TEST", Set.of("COMMERCIAL"));
  @Autowired OaQuotationService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;
  @SpyBean OaMessageRepository repository;

  @AfterEach
  void resetSpy() {
    reset(repository);
  }

  ObjectNode request(int n) throws Exception {
    var r = OaQuotationRequestMapperTest.sample(n);
    String id = UUID.randomUUID().toString();
    return r.put("requestId", "REQ-" + id)
        .put("formNo", "I01-" + id);
  }

  @Test
  void sixFormsPersistAllSourceFieldsAndReturnMappings() throws Exception {
    for (int n = 1; n <= 6; n++) {
      var r = request(n);
      JsonNode result = service.receive(PEER, r.toString());
      var d = result.path("data");
      assertThat(result.path("code").asText()).isEqualTo("0");
      assertThat(d.path("status").asText()).isEqualTo("SUCCEEDED");
      long id = d.path("quoteId").asLong(),
          item = d.path("itemMappings").get(0).path("quoteItemId").asLong();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_oa_form_header_extra_field WHERE oa_form_id=?",
                  Integer.class,
                  id))
          .isEqualTo(r.path("mainData").size());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_oa_form_item_extra_field WHERE oa_form_item_id=? AND"
                      + " source_field_path LIKE 'detailData[0].fields.%'",
                  Integer.class, item))
          .isEqualTo(r.path("detailData").get(0).path("fields").size());
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM lp_quote_bom_status WHERE oa_form_item_id=?",
                  Integer.class,
                  item))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT business_unit_type FROM oa_form WHERE id=?", String.class, id))
          .isEqualTo("COMMERCIAL");
      assertThat(d.path("itemMappings").get(0).path("rowId"))
          .isEqualTo(r.path("detailData").get(0).path("rowId"));
      assertThat(service.receive(PEER, r.toString())).isEqualTo(result);
    }
  }

  @Test
  void sameDocumentCannotOverwriteSourceOrReplaceProductRows() throws Exception {
    var r = request(2);
    var first = service.receive(PEER, r.toString());
    long form = first.path("data").path("quoteId").asLong();
    long item = first.path("data").path("itemMappings").get(0).path("quoteItemId").asLong();
    String originalCustomer = jdbc.queryForObject("SELECT customer FROM oa_form WHERE id=?", String.class, form);
    var changed = r.deepCopy();
    ((ObjectNode) changed.path("mainData")).put("khmc", "修改后的客户");
    ((ObjectNode) changed.path("detailData").get(0)).put("rowId", "RECREATED-ROW");
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(changed.toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    assertThat(jdbc.queryForObject("SELECT customer FROM oa_form WHERE id=?", String.class, form)).isEqualTo(originalCustomer);
    assertThat(jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? AND deleted=0", Long.class, form)).containsExactly(item);
    assertThat(jdbc.queryForObject("SELECT external_document_id FROM lp_oa_quote_document WHERE oa_form_id=?", String.class, form)).isEqualTo(r.path("requestId").asText());
    assertThat(jdbc.queryForObject("SELECT source_version FROM lp_oa_quote_document WHERE oa_form_id=?", Long.class, form)).isEqualTo(1L);
    assertThat(service.receive(PEER, r.toString())).isEqualTo(first);
  }

  @Test
  void sameFormNumberWithAnotherOaIdCannotCreateOrOverwriteQuote() throws Exception {
    var r = request(2);
    var first = service.receive(PEER, r.toString());
    var another = r.deepCopy().put("requestId", "ANOTHER-" + UUID.randomUUID());
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(another.toString()))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_NUMBER_OWNED"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE oa_no=?", Integer.class, r.path("formNo").asText())).isEqualTo(1);
    assertThat(service.receive(PEER, r.toString())).isEqualTo(first);
  }

  @Test
  void rejectedFirstAttemptCanBeCorrectedAndSubmittedWithSameOaId() throws Exception {
    var r = request(2);
    var invalid = r.deepCopy();
    ((ObjectNode) invalid.path("mainData")).putArray("sqdw").add("invalid");
    http.perform(post(URL).header("Authorization", TOKEN).contentType("application/json").content(invalid.toString()))
        .andExpect(status().isBadRequest());
    assertThat(service.receive(PEER, r.toString()).path("data").path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void incompleteSourceIsSavedButBlockedAndNoFakeBomIdentity() throws Exception {
    var r = request(1);
    ((ObjectNode) r.path("mainData")).putNull("cpsyb").putNull("sqsj");
    ((ObjectNode) r.path("detailData").get(0).path("fields")).putNull("lh").putNull("shxh");
    var result = service.receive(PEER, r.toString());
    long id = result.path("data").path("quoteId").asLong();
    assertThat(result.path("data").path("inputStatus").asText()).isEqualTo("BLOCKED");
    assertThat(jdbc.queryForObject("SELECT apply_date FROM oa_form WHERE id=?", Object.class, id))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_quote_bom_status WHERE oa_form_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void concurrentSameRequestCreatesOnlyOneQuote() throws Exception {
    String raw = request(2).toString();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var results =
          java.util.stream.IntStream.range(0, 4)
              .mapToObj(i -> pool.submit(() -> service.receive(PEER, raw)))
              .toList();
      JsonNode first = results.getFirst().get(30, TimeUnit.SECONDS);
      for (var result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(first);
    }
  }

  @Test
  void failedReceiptRollsBackAllBusinessWrites() throws Exception {
    var r = request(2);
    doThrow(new DataIntegrityViolationException("injected"))
        .when(repository)
        .completeQuotation(anyLong(), anyString());
    assertThatThrownBy(() -> service.receive(PEER, r.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM oa_form WHERE oa_no=?",
                Integer.class,
                r.path("formNo").asText()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_oa_quote_document WHERE external_document_id=?",
                Integer.class,
                r.path("requestId").asText()))
        .isZero();
  }

  @Test
  void bearerAndJsonErrorsHaveContractShapeAndNeverLeakCredentials() throws Exception {
    var r = request(2);
    http.perform(post(URL).contentType("application/json").content(r.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    http.perform(
            post(URL).header("Authorization", TOKEN).contentType("application/json").content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
    ((ObjectNode) r.path("mainData")).remove("syb");
    http.perform(
            post(URL)
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content(r.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.data.errors[0].field").value("mainData.syb"));
    http.perform(
            post(URL)
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content(request(2).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
  }
}
