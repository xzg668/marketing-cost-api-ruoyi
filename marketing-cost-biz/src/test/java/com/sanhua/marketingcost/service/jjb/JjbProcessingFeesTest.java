package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.ProcessingFee;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Source;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Stored;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class JjbProcessingFeesTest {
  private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  @Test void linkedPartUsesTheSelectedFormulaInputEvenWhenFeeIsZero() throws Exception {
    var price = legacy("VALVE", "[Cu]+[process_fee]", "0", "铜价+未税加工费", "只");
    var row = Row.builder().kind("PART").code("VALVE").unitPrice("1.250000").amount("2.500000")
        .sources(List.of(new Source("lp_price_prepare_item", 10L, "保存来源"))).build();
    var projected = new JjbPricePresentation(json).apply(List.of(row), null,
        List.of(prepared(10L, "VALVE", "MONTHLY_PRICE", monthly("VALVE", price)))).getFirst();
    assertThat(projected.processingFee()).isEqualTo(new ProcessingFee("0", "元/只", "未税"));
    assertThat(projected.unitPrice()).isEqualTo("1.250000");
    assertThat(projected.amount()).isEqualTo("2.500000");
    var unused = legacy("VALVE", "[Cu]", "0.30", "铜价", "只");
    assertThat(new JjbProcessingFees(json, null).fromEvidence(unused, "VALVE")).isNull();
    assertThat(new JjbProcessingFees(json, null).fromEvidence(price, "OTHER")).isNull();
  }

  @Test void workbookUsesTheReferencedFeeColumnAndItsOwnTaxBasis() throws Exception {
    // Archived pre-tax column P is calculated from the gross fee M. The price refers to P.
    try (var workbook = originalWorkbook("(90+P2)", "现含税加工费", 8.5)) {
      var archive = archive(workbook);
      var extractor = extractor(archive);
      assertThat(extractor.fromEvidence(original(workbook), "FOIL"))
          .isEqualTo(new ProcessingFee("7.5", "元/千克", "未税"));
      var price = workbook.getSheetAt(0).getRow(1).getCell(14);
      price.setCellFormula("(90+M2)/1.13");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(price);
      assertThat(extractor.fromEvidence(original(workbook), "FOIL"))
          .isEqualTo(new ProcessingFee("8.5", "元/千克", "含税"));
      price.setCellFormula("90+K2");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(price);
      assertThat(extractor.fromEvidence(original(workbook), "FOIL")).isNull();
      archive.setFileSha256("different");
      assertThat(extractor(archive).fromEvidence(original(workbook), "FOIL")).isNull();
    }
  }

  @Test void selfMadePartShowsOnlyTheActualRawFeeOnTheRightChild() throws Exception {
    try (var workbook = originalWorkbook("(90+M2)/1.13", "加工费", 8.5)) {
      var projection = new JjbProjection(json, mapper(archive(workbook)));
      var raw = monthly("FOIL", original(workbook));
      var recovery = monthly("SCRAP", legacy("SCRAP", "[Cu]+[process_fee]", "100", "含税加工费", "千克"));
      var rawItem = json.createObjectNode().put("childMaterialNo", "FOIL").put("childMaterialName", "铜箔")
          .put("rawInputSnapshotJson", new MonthlyPriceDocumentCodec(json).encode(raw))
          .put("scrapInputSnapshotJson", recovery.toString()).put("costPrice", "0.211890");
      var noFee = json.createObjectNode().put("childMaterialNo", "STEEL").put("childMaterialName", "钢带")
          .put("rawInputSnapshotJson", monthly("STEEL", legacy("STEEL", "[Cu]", "20", "板价", "千克")).toString())
          .put("costPrice", "0.340299");
      var make = json.createObjectNode().put("calculatorVersion", "MAKE_PART_V1").put("materialCode", "PLATE");
      make.putArray("rows").add(rawItem).add(noFee);
      var sheet = project(projection, make);
      assertThat(sheet.rows().get(0).processingFee()).isNull();
      assertThat(sheet.rows().get(0).amount()).isEqualTo("0.552189");
      assertThat(sheet.rows().get(1).code()).isEqualTo("PLATE\n【FOIL】");
      assertThat(sheet.rows().get(1).processingFee()).isEqualTo(new ProcessingFee("8.5", "元/千克", "含税"));
      assertThat(sheet.rows().get(2).processingFee()).isNull();
      make.putArray("rows").add(rawItem);
      var oneChild = project(projection, make).rows().getFirst();
      assertThat(oneChild.displayCode()).isEqualTo("PLATE\n【FOIL】");
      assertThat(oneChild.processingFee()).isEqualTo(new ProcessingFee("8.5", "元/千克", "含税"));
    }
  }

  private JjbReadModel project(JjbProjection projection, JsonNode snapshot) {
    var part = new CostRunPartItem();
    part.setId(1L); part.setPartCode("PLATE"); part.setPartName("A板片");
    part.setQty(java.math.BigDecimal.ONE); part.setAmount(new java.math.BigDecimal("0.552189"));
    part.setPricePrepareItemId(10L);
    var version = new QuoteCostRunVersion();
    version.setId(1L); version.setStatus("SUCCESS");
    return projection.project(new Stored(version, null, List.of(part), List.of()), new OaForm(), new OaFormItem(),
        List.of(), List.of(prepared(10L, "PLATE", "MAKE_PART_PRICE", snapshot)));
  }

  private PricePrepareItem prepared(long id, String material, String type, JsonNode snapshot) {
    var row = new PricePrepareItem();
    row.setId(id); row.setResultRefType(type); row.setMaterialCode(material); row.setInputSnapshotJson(snapshot.toString());
    return row;
  }

  private ObjectNode monthly(String material, JsonNode price) {
    var row = json.createObjectNode().put("unitPriceMultiplier", 1);
    row.putObject("request").putObject("family").put("materialCode", material).put("unit", "千克");
    row.putObject("selection").putObject("fact").putObject("evidence").put("json", price.toString());
    return row;
  }

  private ObjectNode legacy(String material, String expression, String fee, String text, String unit) {
    var root = json.createObjectNode().put("sourceKind", "LINKED_LEGACY");
    var source = root.putObject("source");
    source.putObject("selected").put("materialCode", material).put("unit", unit)
        .put("processFee", fee).put("formulaExprCn", text);
    source.putObject("calculation").put("expression", expression);
    return root;
  }

  private XSSFWorkbook originalWorkbook(String formula, String header, double fee) {
    var workbook = new XSSFWorkbook();
    var sheet = workbook.createSheet("ImportData");
    var labels = List.of("组织", "来源", "供应商名称", "供应商代码", "采购分类", "物料名称", "物料代码", "规格型号",
        "单位", "联动公式", "下料重", "净重", header, "代理费", "单价", "未税加工费", "是否含税");
    var headings = sheet.createRow(0);
    for (int i = 0; i < labels.size(); i++) headings.createCell(i).setCellValue(labels.get(i));
    var row = sheet.createRow(1);
    row.createCell(10).setCellValue(1);
    row.createCell(12).setCellValue(fee);
    row.createCell(14).setCellFormula(formula);
    row.createCell(15).setCellFormula("ROUND(M2/1.13,1)");
    FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
    evaluator.evaluateAll();
    return workbook;
  }

  private ObjectNode original(XSSFWorkbook workbook) {
    var sheet = workbook.getSheetAt(0);
    var price = sheet.getRow(1).getCell(14);
    var source = json.createObjectNode().put("sourceKind", "LINKED").putObject("source");
    var selected = source.putObject("selected").put("id", 7).put("materialCode", "FOIL").put("unit", "人民币,千克")
        .put("orgCode", "220").put("businessUnitType", "COMMERCIAL").put("expressionVersion", "ORIGINAL_V1")
        .put("sourceSheetName", sheet.getSheetName()).put("sourceRowNumber", 2).put("sourceFormulaCellRef", "O2")
        .put("sourceFormulaExpr", price.getCellFormula()).put("sourceUploadBatchId", 1);
    var rule = json.createObjectNode().put("sheet", sheet.getSheetName()).put("row", 2).put("cell", "O2")
        .put("sourceFormula", price.getCellFormula()).put("taxIncluded", false);
    rule.putObject("identity").put("businessUnitType", "COMMERCIAL").put("orgCode", "220")
        .put("materialCode", "FOIL").put("unit", "人民币,千克");
    rule.set("compiled", json.valueToTree(new WorkbookFormulaCompiler(workbook, "origin.xlsx", Map.of())
        .compile(new WorkbookFormulaCompiler.Address(sheet.getSheetName(), "O2"))));
    selected.put("sourceInputSnapshotJson", json.createObjectNode().set("rule", rule).toString());
    return json.createObjectNode().put("sourceKind", "LINKED").set("source", source);
  }

  private JjbProcessingFees extractor(FactorUploadBatch batch) { return new JjbProcessingFees(json, mapper(batch)); }

  private FactorUploadBatchMapper mapper(FactorUploadBatch archive) {
    // Initialize MyBatis-Plus lambda metadata in this unit test without a database.
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
        new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
        FactorUploadBatch.class);
    var mapper = mock(FactorUploadBatchMapper.class);
    when(mapper.selectOne(any())).thenReturn(archive);
    return mapper;
  }

  private FactorUploadBatch archive(XSSFWorkbook workbook) throws Exception {
    var stream = new ByteArrayOutputStream(); workbook.write(stream);
    var result = new FactorUploadBatch(); result.setSourceWorkbook(stream.toByteArray());
    result.setFileSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.getSourceWorkbook())));
    return result;
  }
}
