package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TechnicalDataSubmissionRemarkTest {
  private final ObjectMapper json = new ObjectMapper();
  private final TechnicalDataVersionContentCodec codec = new TechnicalDataVersionContentCodec(json);
  private final TechnicalDataSubmissionRemark remarks = new TechnicalDataSubmissionRemark(json);

  @Test void rendersAllNineModulesFromTheProductionSnapshotFormat() throws Exception {
    var submission = fixture();
    String result = remarks.generate("1053900000078", submission);
    assertThat(result).startsWith("产品1053900000078：产品资料[")
        .contains("属性新品", "工装1.2元/件", "模具0元/件", "认证2.3元/件",
            "电子图库明细表[", "P001/阀体→C001/铜管", "数量2只/母件", "重量5g", "材质铜",
            "制造件原材料[M001→R001，净长18.5mm，毛重0.004kg，用量0.002kg/制造件]",
            "包装[参考PK-REF，母件PK001×0.5/产品", "纸箱BOX001×2张/组件",
            "辅料[", "清洗剂", "0.28元/只", "参考料号AUX-REF",
            "焊料[参考料号SOLDER-REF，S001用量0.0001kg/产品]",
            "工资[参考料号SAL-REF，直接人工1.25元/只，辅助人工0.3元/只]",
            "净损失率[2.345%]", "不含税单价0.0000123元/kg",
            "不含税公式(元/只)=铜价*毛重+加工费", "下料重1.23456789克", "加工费0元/只", "备注小批量");
    assertThat(result).doesNotContain("sourceReference", "fingerprint", "schemaVersion", "READY", "9999", "900.99", "9.99", "null", "\n", "E-");
    assertThat(result.length()).as("九模块样例通过紧凑格式控制长度，未截断任何明细").isLessThan(1100);
  }

  @Test void onlyIncludesThisPersonsSubmittedModulesInBusinessOrder() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"NET_LOSS\",\"SALARY\"]");
    assertThat(remarks.generate("P1", submission)).isEqualTo(
        "产品P1：工资[参考料号SAL-REF，直接人工1.25元/只，辅助人工0.3元/只]；净损失率[2.345%]。");
  }

  @Test void rendersSubmittedAmountsInsteadOfReferenceAmounts() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"AUXILIARY\",\"SALARY\"]");
    assertThat(remarks.generate("P1", submission)).contains("0.28元/只", "1.25元/只", "0.3元/只")
        .doesNotContain("9.99", "900.99", "9999");
  }

  @Test void keepsZeroAndFullDecimalPrecisionWithoutInventingAbsentValues() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"NET_LOSS\",\"PRICE\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/supplements/netLoss")).put("rate", BigDecimal.ZERO);
    ((ObjectNode) root.at("/supplements/prices/items/0")).put("unitPrice", new BigDecimal("123456789.123456789123"));
    ((ObjectNode) root.at("/supplements/prices/items/1/parameters")).putNull("netWeight");
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).contains("净损失率[0%]", "123456789.123456789123元/kg", "加工费0元/只")
        .doesNotContain("净重0", "null");
  }

  @Test void usesOriginalQuantityUnitsWithoutApplyingPackagingParentQuantityTwice() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"PACKAGE\"]");
    assertThat(remarks.generate("P1", submission)).contains("母件PK001×0.5/产品", "纸箱BOX001×2张/组件")
        .doesNotContain("BOX001×1张/组件", "单价", "金额");
  }

  @Test void fourU9PackageChildrenFitOaRemarkWithoutLosingQuantities() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"PACKAGE\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/supplements/packaging"))
        .put("referenceMaterialNo", "1008900002049")
        .put("parentMaterialNo", "9830000025611")
        .put("parentQuantity", 2);
    var rows = ((ObjectNode) root.get("details")).putArray("packageItems");
    String[][] children = {{"250012749", "外包装箱", "1"}, {"250021194", "隔档", "2"},
        {"250030751", "垫板", "3"}, {"250090015", "塑料袋", "8"}};
    for (String[] child : children) {
      rows.addObject().put("componentMaterialNo", child[0]).put("componentName", child[1])
          .put("quantity", new BigDecimal(child[2])).put("originalUnit", "只")
          .put("sourceSnapshotJson", "{}");
    }
    submission.setContentSnapshotJson(root.toString());
    String remark = remarks.generate("1099999003001", submission);
    assertThat(remark.codePointCount(0, remark.length())).isLessThanOrEqualTo(200);
    assertThat(remark).contains("参考1008900002049", "母件9830000025611×2/产品",
        "外包装箱250012749×1只/组件", "隔档250021194×2只/组件",
        "垫板250030751×3只/组件", "塑料袋250090015×8只/组件");
  }

  @Test void noCodePackageChildKeepsItsModelAndSpecification() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"PACKAGE\"]");
    ObjectNode root = tree(submission);
    var item = (ObjectNode) root.at("/details/packageItems/0");
    item.putNull("componentMaterialNo");
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).contains("纸箱/BOX-M/20×30×2张/组件");
  }

  @Test void uploadedSalaryUsesAlreadyConvertedYuanAndRetainsSeparateIndirectReference() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"SALARY\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/details/salaryItems/0")).put("sourceSnapshotJson", """
        {"laborType":"DIRECT","reference":null,"upload":{"amountFen":"125","amountYuan":"1.25"}}
        """);
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).isEqualTo(
        "产品P1：工资[直接人工1.25元/只，辅助人工0.3元/只(参考料号SAL-REF)]。");
  }

  @Test void drawingCanHaveUnmatchedMaterialsAndNoQuantityUnitAsInProduction() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"DRAWING_BOM\"]");
    ObjectNode root = tree(submission);
    for (var row : root.at("/supplements/drawingBom/nodes")) {
      ((ObjectNode) row).putNull("unit").putNull("materialNo");
    }
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).contains("阀体→铜管", "数量2/母件")
        .doesNotContain("2只", "2kg", "null");
  }

  @Test void briefRemarkKeepsFourActualChangesWithinOaLimitForLargeDrawing() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"PROFILE\",\"DRAWING_BOM\",\"AUXILIARY\",\"SOLDER\"]");
    ObjectNode root = tree(submission);
    var drawing = (ObjectNode) root.at("/supplements/drawingBom");
    drawing.putObject("evidence").put("drawingNo", "RFGB08-0.35-1528");
    var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) drawing.get("nodes");
    for (int index = 2; index < 25; index++) {
      nodes.addObject().put("itemKey", "child-" + index).put("parentItemKey", "root")
          .put("name", "图库零件" + index).put("quantityPerParent", 1);
    }
    submission.setContentSnapshotJson(root.toString());

    String full = remarks.generate("1053900000078", submission);
    String brief = remarks.generateBrief("1053900000078", submission);
    assertThat(full.codePointCount(0, full.length())).isGreaterThan(200);
    assertThat(brief.codePointCount(0, brief.length())).isLessThanOrEqualTo(200);
    assertThat(brief).contains("属性新品", "工装1.2/模具0/认证2.3元/件",
        "图号RFGB08-0.35-1528，25项明细", "清洗剂0.28元/只", "S001×0.0001kg/件");
  }

  @Test void aFormulaDoesNotRequireUnfilledOptionalParameters() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"PRICE\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/supplements/prices/items/1")).putNull("parameters").put("formula", "铜价*0.002");
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).contains("不含税公式(元/只)=铜价*0.002")
        .doesNotContain("下料重", "加工费0", "null");
  }

  @ParameterizedTest
  @ValueSource(ints = {6, 7, 8})
  void sixToEightModulesRetainActualValuesWithinOaLimit(int moduleCount) throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson(moduleCount == 8
        ? "[\"PROFILE\",\"DRAWING_BOM\",\"MANUFACTURING\",\"PACKAGE\",\"AUXILIARY\",\"SOLDER\",\"SALARY\",\"NET_LOSS\"]"
        : moduleCount == 7
        ? "[\"PROFILE\",\"DRAWING_BOM\",\"PACKAGE\",\"AUXILIARY\",\"SOLDER\",\"SALARY\",\"NET_LOSS\"]"
        : "[\"PROFILE\",\"DRAWING_BOM\",\"PACKAGE\",\"AUXILIARY\",\"SOLDER\",\"SALARY\"]");
    ObjectNode root = tree(submission);
    root.withObject("/details").put("productProperty", "非标品");
    root.withObject("/supplements/drawingBom/evidence").put("drawingNo", "RFGB08-0.35-1528");
    var drawing = (com.fasterxml.jackson.databind.node.ArrayNode) root.at("/supplements/drawingBom/nodes");
    for (int index = 2; index < 25; index++) drawing.addObject().put("itemKey", "child-" + index)
        .put("parentItemKey", "root").put("name", "图库零件" + index).put("quantityPerParent", 1);
    root.withObject("/supplements/packaging").put("parentMaterialNo", "9830000025611").put("parentQuantity", 2);
    var packages = root.withObject("/details").putArray("packageItems");
    for (int index = 0; index < 4; index++) packages.addObject();
    root.withObject("/details/auxiliaryItems/0").put("auxiliaryName", "其他辅料").put("amount", new BigDecimal("0.06"));
    root.withObject("/supplements/solder/items/0").put("materialNo", "209900282").put("quantityPerProduct", new BigDecimal("0.0005")).put("unit", "千克");
    root.withObject("/details/salaryItems/0").put("amount", new BigDecimal("12.583245"));
    root.withObject("/details/salaryItems/1").put("amount", new BigDecimal("0.322200"));
    root.withObject("/supplements/netLoss").put("rate", new BigDecimal("0.00475"));
    submission.setContentSnapshotJson(root.toString());

    String brief = remarks.generateBrief("1099999015001", submission);
    assertThat(brief.codePointCount(0, brief.length())).isLessThanOrEqualTo(200);
    assertThat(brief).contains("属性非标品", "工装1.2/模具0/认证2.3元/件",
        "RFGB08-0.35-1528，25项明细", "母件9830000025611×2，子件4项",
        "其他辅料0.06元/只", "209900282×0.0005千克/件", "工资[直接12.583245/辅助0.3222元/只]");
    if (moduleCount >= 7) assertThat(brief).contains("净损失率[0.475%]");
    if (moduleCount == 8) assertThat(brief).contains("制造件原材料[1项原料]");
    assertThat(brief).doesNotContain("...", "省略", "参考料号");
  }

  @Test void differentSalaryReferencesRemainAttachedToTheirOwnAmounts() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"SALARY\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/details/salaryItems/1")).put("sourceSnapshotJson", "{\"reference\":{\"materialNo\":\"INDIRECT-REF\"}}");
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).isEqualTo(
        "产品P1：工资[直接人工1.25元/只(参考料号SAL-REF)，辅助人工0.3元/只(参考料号INDIRECT-REF)]。");
  }

  @Test void sourceMetadataAndUnsubmittedModulesDoNotLeakIntoRemark() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"NET_LOSS\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/supplements/netLoss")).putObject("reference").putObject("source").put("materialNo", "LOSS-REF").put("rate", new BigDecimal("0.99"));
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate("P1", submission)).isEqualTo("产品P1：净损失率[2.345%，参考料号LOSS-REF]。");
  }

  @Test void everyLineIsRetainedEvenWhenManyMaterialsAreSubmitted() throws Exception {
    var submission = fixture();
    submission.setModuleTypesJson("[\"SOLDER\"]");
    ObjectNode root = tree(submission);
    var items = ((ObjectNode) root.at("/supplements/solder")).putArray("items");
    for (int index = 0; index < 100; index++) items.addObject().put("materialNo", "SOLDER-" + index)
        .put("quantityPerProduct", new BigDecimal("0.00000001")).put("unit", "kg");
    submission.setContentSnapshotJson(root.toString());
    String result = remarks.generate("P1", submission);
    assertThat(result).contains("SOLDER-0用量0.00000001kg/产品", "SOLDER-99用量0.00000001kg/产品")
        .doesNotContain("...", "省略", "等");
    assertThat(result.split("kg/产品", -1)).hasSize(101);
  }

  @Test void repeatedGenerationReadsFrozenJsonWithoutMutatingSubmission() throws Exception {
    var submission = fixture();
    String frozen = submission.getContentSnapshotJson();
    String before = remarks.generate("P1", submission);
    ObjectNode unrelatedDraft = tree(submission);
    ((ObjectNode) unrelatedDraft.at("/details/salaryItems/0")).put("amount", 100);
    assertThat(remarks.generate("P1", submission)).isEqualTo(before);
    assertThat(submission.getContentSnapshotJson()).isEqualTo(frozen);
  }

  @ParameterizedTest @ValueSource(strings = {"[]", "[\"UNKNOWN\"]", "[\"SALARY\",\"SALARY\"]", "{}", "[null]"})
  void rejectsInvalidSubmissionScope(String modules) throws Exception {
    var submission = fixture(); submission.setModuleTypesJson(modules);
    assertThatThrownBy(() -> remarks.generate("P1", submission)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectsScopeMissingFromTheFrozenSnapshot() throws Exception {
    var submission = fixture();
    ObjectNode root = tree(submission);
    ((ObjectNode) root.get("details")).putArray("modules");
    submission.setContentSnapshotJson(root.toString());
    assertThatThrownBy(() -> remarks.generate("P1", submission)).hasMessageContaining("与冻结快照不一致");
  }

  @ParameterizedTest @ValueSource(strings = {"{", "null", "{\"schemaVersion\":1}", "{\"schemaVersion\":2}"})
  void rejectsMalformedOrUnsupportedSnapshot(String value) throws Exception {
    var submission = fixture(); submission.setContentSnapshotJson(value);
    assertThatThrownBy(() -> remarks.generate("P1", submission)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void missingAmountIsAnErrorRatherThanZero() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"SALARY\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/details/salaryItems/0")).putNull("amount");
    submission.setContentSnapshotJson(root.toString());
    assertThatThrownBy(() -> remarks.generate("P1", submission)).hasMessageContaining("amount数值缺失");
  }

  @Test void normalizesLineBreaksButPreservesHumanNotesAndCurrency() throws Exception {
    var submission = fixture(); submission.setModuleTypesJson("[\"PRICE\"]");
    ObjectNode root = tree(submission);
    ((ObjectNode) root.at("/supplements/prices/items/0")).put("currency", "USD").put("notes", "试制\n 小批量\t报价");
    submission.setContentSnapshotJson(root.toString());
    assertThat(remarks.generate(" P1\n ", submission)).startsWith("产品P1：").contains("USD/kg", "备注试制 小批量 报价")
        .doesNotContain("\n", "\t");
  }

  private ObjectNode tree(QuoteTechSubmission submission) throws Exception {
    return (ObjectNode) json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .readTree(submission.getContentSnapshotJson());
  }

  /** 使用生产 codec 构造冻结报文，避免测试自造另一套字段结构。 */
  private QuoteTechSubmission fixture() throws Exception {
    var version = new QuoteTechDataVersion();
    version.setContentSchemaVersion(2); version.setProductModel("MODEL-1"); version.setProductProperty("新品");
    version.setProductFeesJson("""
        {"includesNewToolingMouldCertificationFee":true,"unitToolingFee":1.20,"unitMouldFee":0,"unitCertificationFee":2.3,"currency":"CNY"}
        """);
    version.setDrawingBomJson("""
        {"sourceVersionId":9999,"nodes":[
        {"itemKey":"root","materialNo":"P001","name":"阀体","quantityPerParent":1,"unit":"只"},
        {"itemKey":"child","parentItemKey":"root","materialNo":"C001","name":"铜管","drawingNo":"D01","specification":"6×0.5",
         "quantityPerParent":2,"unit":"只","sourceWeight":5,"sourceWeightUnit":"g","sourceMaterial":"铜","sourceRemark":"试制"}]}
        """);
    version.setManufacturingJson("""
        {"items":[{"parentMaterialNo":"M001","rawMaterialNo":"R001","netLengthMm":18.5,"grossWeightKg":0.004,"quantityPerParent":0.002,"unit":"kg"}]}
        """);
    version.setPackagingJson("""
        {"referenceMaterialNo":"PK-REF","parentMaterialNo":"PK001","parentQuantity":0.5,"parentQuantityUnit":"组件/件产品","entryMode":"REFERENCE"}
        """);
    version.setSolderItemsJson("""
        {"entryMode":"REFERENCE","reference":{"materialNo":"SOLDER-REF"},"items":[{"materialNo":"S001","quantityPerProduct":0.000100,"unit":"kg"}]}
        """);
    version.setNetLossJson("""
        {"rate":0.02345,"entryMode":"MANUAL"}
        """);
    version.setPriceItemsJson("""
        {"items":[{"materialNo":"R001","organizationCode":"ORG1","currency":"CNY","unit":"kg","entryMode":"FIXED","unitPrice":0.000012300},
        {"materialNo":"C001","organizationCode":"ORG1","currency":"CNY","unit":"只","entryMode":"MANUAL","formula":"铜价*毛重+加工费",
        "parameters":{"blankWeight":1.23456789,"netWeight":1.1,"weightUnit":"克","processFee":0,"agentFee":0.002,"feeUnit":"元/只"},"notes":"小批量"}]}
        """);
    var packaging = new QuoteTechPackageItem();
    packaging.setLineNo(1); packaging.setComponentMaterialNo("BOX001"); packaging.setComponentName("纸箱"); packaging.setComponentSpec("20×30");
    packaging.setQuantity(new BigDecimal("2.000")); packaging.setOriginalUnit("张");
    packaging.setSourceSnapshotJson("{\"kind\":\"REFERENCE\",\"model\":\"BOX-M\"}");
    var auxiliary = new QuoteTechAuxItem();
    auxiliary.setLineNo(1); auxiliary.setAuxiliaryName("清洗剂"); auxiliary.setSubjectCode("6601"); auxiliary.setSubjectName("清洗剂");
    auxiliary.setPricingMethod("CMS_AMOUNT"); auxiliary.setAmount(new BigDecimal("0.280000"));
    auxiliary.setSourceSnapshotJson("{\"cms\":{\"materialNo\":\"AUX-REF\",\"item\":{\"sourceAmount\":9.99}}}");
    List<QuoteTechSalaryItem> salaries = List.of(salaryItem("DIRECT", "1.25000", 1), salaryItem("INDIRECT", "0.30000", 2));
    var modules = TechnicalDataModuleType.orderedCodes().stream().map(type -> new TechnicalDataVersionContentCodec.ModuleSnapshot(
        type, true, "SUPPLEMENTAL", "READY", "SOURCE", "9999", "VERSION", "fingerprint", null, "VALID", "已校验")).toList();
    var submission = new QuoteTechSubmission();
    submission.setContentSchemaVersion(2); submission.setModuleTypesJson(json.writeValueAsString(TechnicalDataModuleType.orderedCodes()));
    submission.setContentSnapshotJson(codec.versionContentJson(version, modules, List.of(packaging), List.of(auxiliary), salaries));
    return submission;
  }

  private QuoteTechSalaryItem salaryItem(String type, String amount, int line) {
    var item = new QuoteTechSalaryItem(); item.setLineNo(line); item.setLaborType(type); item.setAmount(new BigDecimal(amount));
    item.setSourceSnapshotJson("{\"reference\":{\"materialNo\":\"SAL-REF\",\"direct\":{\"amountYuan\":900.99},\"indirect\":{\"amountYuan\":900.99}}}");
    return item;
  }
}
