package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class QuoteOaPdfTemplateDefinitions {
  private static final String SCOPE_HEADER = "HEADER";
  private static final String SCOPE_ITEM = "ITEM";
  private static final String TABLE_HEADER = "oa_form";
  private static final String TABLE_ITEM = "oa_form_item";
  private static final String TABLE_FEE = "lp_oa_form_extra_fee";

  private static final Map<QuoteExcelTemplateType, QuoteOaPdfTemplateDefinition> DEFINITIONS = build();

  private QuoteOaPdfTemplateDefinitions() {}

  public static QuoteOaPdfTemplateDefinition get(QuoteExcelTemplateType templateType) {
    return DEFINITIONS.get(templateType);
  }

  public static Map<QuoteExcelTemplateType, QuoteOaPdfTemplateDefinition> all() {
    return Map.copyOf(DEFINITIONS);
  }

  private static Map<QuoteExcelTemplateType, QuoteOaPdfTemplateDefinition> build() {
    Map<QuoteExcelTemplateType, QuoteOaPdfTemplateDefinition> definitions =
        new EnumMap<>(QuoteExcelTemplateType.class);
    List<QuoteOaPdfFieldDefinition> headerFields = headerFields();
    List<QuoteOaPdfFieldDefinition> itemFields = itemFields();
    List<QuoteOaPdfFieldDefinition> feeFields = feeFields();
    QuoteOaPdfTableDefinition itemTable =
        new QuoteOaPdfTableDefinition(
            List.of("明细表", "成本明细表", "产品明细"),
            List.of(">>辅助信息", ">>技术员", ">>资料员", ">>审批信息", ">>流程日志"),
            itemFields);
    List<String> sectionAnchors = List.of(">>基础信息", ">>业务信息", ">>明细表", ">>辅助信息");

    put(
        definitions,
        QuoteExcelTemplateType.FI_SC_020,
        "成本核算联系单（板换科技-直销）",
        QuoteClassifyService.BU_COMMERCIAL,
        QuoteClassifyService.CATEGORY_COMMERCIAL_DIRECT,
        null,
        sectionAnchors,
        headerFields,
        itemFields,
        feeFields,
        itemTable);
    put(
        definitions,
        QuoteExcelTemplateType.FI_SC_006,
        "标准品/批量品成本核算流程",
        QuoteClassifyService.BU_COMMERCIAL,
        QuoteClassifyService.CATEGORY_COMMERCIAL_DIRECT,
        "批量品",
        sectionAnchors,
        headerFields,
        itemFields,
        feeFields,
        itemTable);
    put(
        definitions,
        QuoteExcelTemplateType.FI_SC_005,
        "新品成本核算流程",
        QuoteClassifyService.BU_COMMERCIAL,
        QuoteClassifyService.CATEGORY_COMMERCIAL_DIRECT,
        "新品",
        sectionAnchors,
        headerFields,
        itemFields,
        feeFields,
        itemTable);
    put(
        definitions,
        QuoteExcelTemplateType.FI_SR_005_NEW,
        "FI-SR-005 成本核算联系单（新品）",
        QuoteClassifyService.BU_COMMERCIAL,
        QuoteClassifyService.CATEGORY_HOUSEHOLD_PROXY,
        "新品",
        sectionAnchors,
        headerFields,
        itemFields,
        feeFields,
        itemTable);
    put(
        definitions,
        QuoteExcelTemplateType.FI_SR_005_MASS,
        "FI-SR-005 成本核算联系单（批量品）",
        QuoteClassifyService.BU_COMMERCIAL,
        QuoteClassifyService.CATEGORY_HOUSEHOLD_PROXY,
        "批量品",
        sectionAnchors,
        headerFields,
        itemFields,
        feeFields,
        itemTable);
    put(
        definitions,
        QuoteExcelTemplateType.FI_SR_005_DERIVED,
        "FI-SR-005 成本核算联系单（衍生品）",
        QuoteClassifyService.BU_COMMERCIAL,
        QuoteClassifyService.CATEGORY_HOUSEHOLD_PROXY,
        "衍生品",
        sectionAnchors,
        headerFields,
        itemFields,
        feeFields,
        itemTable);
    return definitions;
  }

  private static void put(
      Map<QuoteExcelTemplateType, QuoteOaPdfTemplateDefinition> definitions,
      QuoteExcelTemplateType templateType,
      String processName,
      String businessUnitType,
      String expenseProductCategory,
      String defaultBusinessType,
      List<String> sectionAnchors,
      List<QuoteOaPdfFieldDefinition> headerFields,
      List<QuoteOaPdfFieldDefinition> itemFields,
      List<QuoteOaPdfFieldDefinition> feeFields,
      QuoteOaPdfTableDefinition itemTable) {
    definitions.put(
        templateType,
        new QuoteOaPdfTemplateDefinition(
            templateType,
            processName,
            businessUnitType,
            expenseProductCategory,
            defaultBusinessType,
            sectionAnchors,
            headerFields,
            itemFields,
            feeFields,
            itemTable));
  }

  private static List<QuoteOaPdfFieldDefinition> headerFields() {
    return List.of(
        header("processTitle", "流程标题", "基础信息", "流程标题"),
        header("urgency", "紧急程度", "基础信息", "紧急程度"),
        header("oaNo", "流程编号", "基础信息", "流程编号", "单据编号", "OA编号"),
        header("applicantName", "申请人", "基础信息", "申请人", "发起人"),
        header("applyDate", "申请日期", "基础信息", "申请日期", "申请时间"),
        header("employeeNo", "工号", "基础信息", "工号", "申请人工号"),
        header("applicantUnit", "申请单位", "基础信息", "申请单位"),
        header("applicantDept", "申请部门", "基础信息", "申请部门"),
        header("applicantOffice", "申请处室", "基础信息", "申请处室"),
        header("sourceCompany", "法人组织", "基础信息", "法人组织", "公司"),
        header("sourceBusinessDivision", "企业编码", "基础信息", "企业编码", "事业部"),
        header("handlerName", "经办人", "基础信息", "经办人"),
        header("customer", "客户名称", "业务信息", "客户名称", "客户"),
        header("customerType", "客户类型", "业务信息", "客户类型"),
        header("businessType", "业务类型", "业务信息", "业务类型"),
        header("productAttr", "产品属性", "业务信息", "产品属性"),
        header("replyDateRequiredBySales", "销售部门要求回复日期", "业务信息", "销售部门要求回复日期", "客户要求回复日期"),
        header("tradeTerms", "贸易条款", "业务信息", "贸易条款"),
        header("exchangeRate", "汇率", "业务信息", "汇率"),
        header("priceLinkMode", "销售价格联动情况", "业务信息", "销售价格是否联动", "销售价格联动情况"),
        header("overseasSalesMode", "是否通过海外三花销售", "业务信息", "是否通过海外仓库发终端客户", "是否通过海外三花销售"),
        header("copperPrice", "铜价", "业务信息", "核算时铜基价（含税，元/吨）", "铜", "铜价"),
        header("zincPrice", "锌价", "业务信息", "核算时锌基价（含税，元/吨）", "锌", "锌价"),
        header("aluminumPrice", "铝价", "业务信息", "核算时铝基价（含税，元/吨）", "铝", "铝价"),
        header("sus304Price", "SUS304", "业务信息", "核算时SUS304基价（含税，元/吨）", "SUS304"),
        header("sus316lPrice", "SUS316", "业务信息", "核算时SUS316基价（含税，元/吨）", "SUS316", "SUS316L"),
        header("steelPrice", "不锈钢", "业务信息", "不锈钢", "钢"),
        header("silverPrice", "银价", "业务信息", "银", "银价"),
        header("otherMaterial", "其他材料价", "业务信息", "其他材料价", "其他材料"),
        header("baseShipping", "基准运输费", "业务信息", "基准运输费", "运费核算标准", "海运费核算标准"),
        header("technicianName", "技术员", "业务信息", "技术员"),
        header("remark", "备注", "业务信息", "备注"));
  }

  private static List<QuoteOaPdfFieldDefinition> itemFields() {
    return List.of(
        item("seq", "序号", "序号", "No."),
        item("productName", "产品名称", "产品名称", "品名"),
        item("customerDrawing", "客户图号", "客户图号", "图号"),
        item("customerCode", "客户编码", "客户编码", "客户物料号"),
        item("materialNo", "料号", "料号", "物料号", "三花物料号"),
        item("sunlModel", "三花型号", "三花型号", "型号"),
        item("spec", "规格", "规格", "规格型号"),
        item("businessType", "业务类型", "业务类型"),
        item("packageMethod", "包装方式", "包装方式"),
        item("shippingFee", "运费", "运费", "运输费"),
        item("supportQty", "配套量", "配套量"),
        item("annualVolume", "年用量", "年用量", "年需求量"),
        item("totalWithShip", "含运费总价", "含运费总价", "总价（含运费）"),
        item("totalNoShip", "不含运费总价", "不含运费总价", "总价（不含运费）"),
        item("materialCost", "材料成本", "材料成本", "材料费"),
        item("laborCost", "人工成本", "人工成本", "人工费"),
        item("manufacturingCost", "制造费用", "制造费用", "制费"),
        item("managementCost", "管理费用", "管理费用", "管费"),
        item("validMonth", "有效月数", "有效月数", "有效期（月）"),
        item("validDate", "有效日期", "有效日期", "有效期"),
        item("sus304WeightG", "SUS304重量", "SUS304重量", "SUS304(g)"),
        item("sus316WeightG", "SUS316重量", "SUS316重量", "SUS316(g)"),
        item("copperWeightG", "铜重量", "铜重量", "铜(g)"),
        item("scrapRate", "报废率", "报废率"),
        item("unitLaborCost", "单件人工", "单件人工", "单位人工"),
        item("remark", "备注", "备注"));
  }

  private static List<QuoteOaPdfFieldDefinition> feeFields() {
    return List.of(
        fee("fixtureTotalAmount", "工装夹具费", "工装夹具费", "工装费", "夹具费"),
        fee("moldTotalAmount", "模具费", "模具费", "开模费"),
        fee("toolCost", "刀具费", "刀具费", "刀具费用"),
        fee("certificationFee", "认证费", "认证费", "认证费用"),
        fee("equipmentFee", "设备费", "设备费", "设备费用"));
  }

  private static QuoteOaPdfFieldDefinition header(
      String fieldCode, String fieldName, String section, String... aliases) {
    return new QuoteOaPdfFieldDefinition(
        SCOPE_HEADER, fieldCode, fieldName, section, TABLE_HEADER, List.of(aliases));
  }

  private static QuoteOaPdfFieldDefinition item(String fieldCode, String fieldName, String... aliases) {
    return new QuoteOaPdfFieldDefinition(
        SCOPE_ITEM, fieldCode, fieldName, "明细表", TABLE_ITEM, List.of(aliases));
  }

  private static QuoteOaPdfFieldDefinition fee(String fieldCode, String fieldName, String... aliases) {
    return new QuoteOaPdfFieldDefinition(
        SCOPE_ITEM, fieldCode, fieldName, "明细表", TABLE_FEE, List.of(aliases));
  }
}
