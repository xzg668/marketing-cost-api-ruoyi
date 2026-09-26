package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.ingest.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OaQuotationRequestMapperTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final OaPeer PEER = new OaPeer("WEAVER", "TEST", Set.of("COMMERCIAL"));
  final OaQuotationRequestMapper mapper = new OaQuotationRequestMapper(new OaMessageCodec(JSON));

  static ObjectNode sample(int n) throws Exception {
    try (var in =
        OaQuotationRequestMapperTest.class.getResourceAsStream(
            "/fixtures/oa-i01/case-" + n + ".json")) {
      return (ObjectNode) JSON.readTree(in);
    }
  }

  @Test
  void oaRequestIdIsTheOnlyDocumentIdentityAndRemovedFieldsAreRejected() throws Exception {
    var root = sample(1).put("requestId", "000123456789");
    var mapped = mapper.map(root, PEER);
    assertThat(mapped.quote().documentId()).isEqualTo("000123456789");
    assertThat(mapped.quote().request().getExternalFormNo()).isEqualTo("000123456789");
    assertThat(mapped.quote().request().getVersion()).isEqualTo("1");
    for (String removed : new String[] {"workflowRequestId", "version"}) {
      var invalid = root.deepCopy().put(removed, "1");
      assertThatThrownBy(() -> mapper.map(invalid, PEER)).isInstanceOf(OaQuotationValidationException.class);
    }
  }

  @Test
  void sixWordExamplesMatchAllFieldsAndExistingClassifier() throws Exception {
    int[] headerCounts = {45, 32, 26, 41, 59, 41}, detailCounts = {22, 31, 17, 18, 15, 15};
    for (int n = 1; n <= 6; n++) {
      var source = sample(n);
      var mapped = mapper.map(source, PEER);
      var request = mapped.quote().request();
      assertThat(request.getExtraFields()).hasSize(headerCounts[n - 1]);
      assertThat(request.getItems().getFirst().getExtraFields().size())
          .isGreaterThanOrEqualTo(detailCounts[n - 1] + 2);
      assertThat(new QuoteIngestRequestValidator().validate(request).getErrors()).isEmpty();
      var classification = new QuoteClassifyService().classify(request);
      assertThat(classification.getBusinessUnitType()).isEqualTo("COMMERCIAL");
      assertThat(classification.getClassificationStatus()).isEqualTo("CONFIRMED");
    }
  }

  @Test
  void sc006RealHeaderKeepsFeeDimensionsSeparateAndAll81Rows() throws Exception {
    var root = sample(2);
    var m = (ObjectNode) root.path("mainData");
    m.put("sqdw", "商用制冷业务单元")
        .put("sqbm", "亚太营销本部")
        .put("sqcs", "华北业务部")
        .put("syb", "商用部品事业部")
        .put("ssgs", "浙江三花商用制冷有限公司");
    var row = root.path("detailData").get(0).deepCopy();
    var rows = root.putArray("detailData");
    for (int i = 1; i <= 81; i++)
      rows.add(((ObjectNode) row.deepCopy()).put("rowId", "ROW-" + i).put("rowIndex", i));
    var mapped = mapper.map(root, PEER);
    var h = mapped.quote().request().getHeader();
    assertThat(mapped.quote().request().getItems()).hasSize(81);
    assertThat(h.getApplicantUnit()).isEqualTo("商用制冷业务单元");
    assertThat(h.getApplicantDept()).isEqualTo("亚太营销本部");
    assertThat(h.getApplicantOffice()).isEqualTo("华北业务部");
    assertThat(h.getSourceBusinessDivision()).isEqualTo("商用部品事业部");
    assertThat(h.getSourceCompany()).isEqualTo("浙江三花商用制冷有限公司");
  }

  @Test
  void srModelOnlyAndCustomerDrawingHaveIndependentIdentity() throws Exception {
    var r = sample(6);
    var m = (ObjectNode) r.path("mainData");
    m.put("sqdw", "家用制冷事业本部（总部）").put("sqbm", "泰国事务所").put("sssybdx", "板换事业部");
    ((ObjectNode) r.path("detailData").get(0).path("fields"))
        .put("lh", "/")
        .put("shxh", "S12BH-12L-18")
        .put("khmc", "DRAW-001");
    var request = mapper.map(r, PEER).quote().request();
    var v = request.getItems().getFirst();
    assertThat(v.getMaterialNo()).isNull();
    assertThat(v.getSunlModel()).isEqualTo("S12BH-12L-18");
    assertThat(v.getCustomerDrawing()).isEqualTo("DRAW-001");
    assertThat(request.getHeader().getApplicantOffice()).isNull();
    assertThat(new QuoteClassifyService().classify(request).getBusinessUnitType())
        .isEqualTo("COMMERCIAL");
    assertThat(v.getExtraFields())
        .anySatisfy(
            f -> {
              assertThat(f.getFieldCode()).isEqualTo("lh");
              assertThat(f.getFieldValue()).isEqualTo("/");
            });
  }

  @Test
  void volumeAndCostSemanticsDifferAcrossForms() throws Exception {
    var sc20 = mapper.map(sample(3), PEER).quote().request().getItems().getFirst();
    assertThat(sc20.getAnnualVolume()).isEqualTo("1.2000");
    assertThat(sc20.getSus304WeightG()).isEqualTo("200");
    var sr = mapper.map(sample(4), PEER).quote().request().getItems().getFirst();
    assertThat(sr.getSupportQty()).isEqualTo("1.2");
    assertThat(sr.getAnnualVolume()).isNull();
    var r = sample(6);
    ((ObjectNode) r.path("detailData").get(0).path("fields"))
        .put("hysfzcbbhs", 196.278)
        .put("zcbbhs", 190.987);
    var v = mapper.map(r, PEER).quote().request().getItems().getFirst();
    assertThat(v.getTotalWithShip()).isEqualTo("196.278");
    assertThat(v.getTotalNoShip()).isEqualTo("190.987");
  }

  @Test
  void amountsKeepScopeUnitAndUnknownProductStates() throws Exception {
    var r = sample(1);
    ((ObjectNode) r.path("detailData").get(0).path("fields")).put("gzmjfwy", 2);
    var q = mapper.map(r, PEER).quote().request();
    assertThat(q.getExtraFees())
        .anySatisfy(
            f -> {
              assertThat(f.getFeeCode()).isEqualTo("gzjjfyzjey");
              assertThat(f.getAmount()).isEqualTo("10000");
              assertThat(f.getUnit()).isEqualTo("元");
            });
    assertThat(q.getItems().getFirst().getExtraFees())
        .anySatisfy(
            f -> {
              assertThat(f.getFeeCode()).isEqualTo("gzmjfwy");
              assertThat(f.getAmount()).isEqualTo("2");
              assertThat(f.getUnit()).isEqualTo("万元");
            });
    assertThat(q.getItems().getFirst().getProductStatus()).isNull();
    assertThat(q.getExtraFields().stream().filter(f -> f.getFieldCode().startsWith("cpzt")))
        .hasSize(2);
  }

  @Test
  void timestampIsSplitWithoutDroppingOriginalTime() throws Exception {
    var r = sample(1);
    ((ObjectNode) r.path("mainData")).put("sqsj", "2026-05-25 17:34");
    var q = mapper.map(r, PEER).quote().request();
    assertThat(q.getHeader().getApplyDate()).isEqualTo("2026-05-25");
    assertThat(q.getExtraFields())
        .anySatisfy(
            f -> {
              assertThat(f.getFieldCode()).isEqualTo("sqsj");
              assertThat(f.getFieldValue()).isEqualTo("2026-05-25 17:34");
            });
  }

  @Test
  void nullBusinessInputsReportGapsWithoutFabricatingValues() throws Exception {
    var r = sample(1);
    ((ObjectNode) r.path("mainData")).putNull("cpsyb").putNull("sqsj");
    var mapped = mapper.map(r, PEER);
    assertThat(mapped.issues())
        .extracting(OaQuotationResponse.InputIssue::field)
        .contains("mainData.cpsyb", "mainData.sqsj");
    assertThat(new QuoteIngestRequestValidator().validate(mapped.quote().request()).getErrors())
        .isEmpty();
  }

  @Test
  void missingKeysArraysAndUnknownFieldsFailAtSourcePath() throws Exception {
    var r = sample(2);
    ((ObjectNode) r.path("mainData")).remove("syb");
    assertThatThrownBy(() -> mapper.map(r, PEER))
        .isInstanceOf(OaQuotationValidationException.class)
        .hasMessageContaining("事业部");
    var a = sample(2);
    ((ObjectNode) a.path("mainData")).putArray("sqdw").add("事业部");
    assertThatThrownBy(() -> mapper.map(a, PEER)).hasMessageContaining("单个字符串");
    var u = sample(2);
    ((ObjectNode) u.path("mainData")).put("sssyb", "废弃");
    assertThatThrownBy(() -> mapper.map(u, PEER)).hasMessageContaining("不属于");
  }

  @Test
  void stableRowIdsSurviveReorderingAndDuplicateRowsFail() throws Exception {
    var r = sample(2);
    var row = (ObjectNode) r.path("detailData").get(0);
    String original = mapper.map(r, PEER).lines().getFirst().externalLineId();
    row.put("rowIndex", 2);
    assertThat(mapper.map(r, PEER).lines().getFirst().externalLineId()).isEqualTo(original);
    ((com.fasterxml.jackson.databind.node.ArrayNode) r.path("detailData"))
        .add(row.deepCopy().put("rowIndex", 3));
    assertThatThrownBy(() -> mapper.map(r, PEER)).hasMessageContaining("行标识不能重复");
  }

  @Test
  void storagePrecisionNeverSilentlyChangesSourceNumbers() throws Exception {
    var r = sample(2);
    ((ObjectNode) r.path("detailData").get(0).path("fields")).put("ysfyz", 1.23456);
    assertThatThrownBy(() -> mapper.map(r, PEER)).hasMessageContaining("精度");
    var price = sample(2);
    ((ObjectNode) price.path("mainData")).put("hsstjjhs", 12345.123);
    assertThatThrownBy(() -> mapper.map(price, PEER)).hasMessageContaining("精度");
    var volume = sample(3);
    ((ObjectNode) volume.path("detailData").get(0).path("fields")).put("yjnylz", 0.001);
    assertThatThrownBy(() -> mapper.map(volume, PEER)).hasMessageContaining("精度");
  }

  @Test
  void wrongTableTypesDatesAndPrecisionAreRejected() throws Exception {
    var r = sample(6);
    ((ObjectNode) r.path("detailData").get(0)).put("tableKey", "明细表1");
    assertThatThrownBy(() -> mapper.map(r, PEER)).hasMessageContaining("明细表2");
    var d = sample(1);
    ((ObjectNode) d.path("mainData")).put("sqsj", "2026-02-30");
    assertThatThrownBy(() -> mapper.map(d, PEER)).hasMessageContaining("日期格式");
    var p = sample(1);
    ((ObjectNode) p.path("mainData")).put("hl", 0.1234567);
    assertThatThrownBy(() -> mapper.map(p, PEER)).hasMessageContaining("精度");
  }
}
