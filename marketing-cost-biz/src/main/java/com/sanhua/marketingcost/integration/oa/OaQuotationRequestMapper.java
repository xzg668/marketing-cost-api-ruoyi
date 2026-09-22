package com.sanhua.marketingcost.integration.oa;

import static com.sanhua.marketingcost.integration.oa.OaQuotationFields.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.dto.ingest.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import org.springframework.stereotype.Component;

/** OA 原表单字段在此转换为导入共用对象；数据库和核算服务不依赖 OA 的拼音 key。 */
@Component
public class OaQuotationRequestMapper {
  public record SourceLine(String tableKey, String rowId, String externalLineId) {}

  public record Mapped(
      String requestId,
      OaQuoteRequest quote,
      List<SourceLine> lines,
      List<OaQuotationResponse.InputIssue> issues) {}

  private final OaMessageCodec codec;

  public OaQuotationRequestMapper(OaMessageCodec codec) {
    this.codec = codec;
  }

  public Mapped map(JsonNode root, OaPeer peer) {
    object(
        root,
        "$",
        Set.of(
            "requestId",
            "formNo",
            "processCode",
            "mainData",
            "detailData"));
    String requestId = text(root, "requestId", 128);
    String formNo = text(root, "formNo", 64);
    String process = text(root, "processCode", 20);
    if (!Set.of("FI-SC-005", "FI-SC-006", "FI-SC-020", "FI-SR-005").contains(process))
      fail("processCode", "不支持的表单模板编码");
    JsonNode main = root.path("mainData");
    boolean sr = process.equals("FI-SR-005"),
        sc5 = process.equals("FI-SC-005"),
        sc6 = process.equals("FI-SC-006");
    String business = sc5 ? "新品" : value(main, sr ? "ywlx" : sc6 ? "ywlx1" : "ywlx");
    if (sr && !Set.of("批量品", "新品", "衍生品").contains(Objects.toString(business, "")))
      fail("mainData.ywlx", "SR业务类型必须为批量品、新品或衍生品");
    if (!sr && !sc5 && business != null && !Set.of("标准品", "批量品").contains(business))
      fail("mainData." + (sc6 ? "ywlx1" : "ywlx"), "业务类型应为标准品或批量品");
    boolean batch = "批量品".equals(business);
    List<Field> headers =
        sr
            ? concat(SR_MAIN, "新品".equals(business) ? SR_NEW_MAIN : SR_BATCH_MAIN)
            : sc5 ? SC005_MAIN : sc6 ? SC006_MAIN : SC020_MAIN;
    List<Field> details =
        sr ? batch ? SR_BATCH_ITEM : SR_NEW_ITEM : sc5 ? SC005_ITEM : sc6 ? SC006_ITEM : SC020_ITEM;
    validateFields(main, headers, "mainData");
    validateStorageValues(main, true, sr && batch, process, "mainData");
    // FI-SR-005 在现有核算里属于商用的家代商口径，不是 HOUSEHOLD 数据隔离域。
    if (!peer.businessUnits().contains("COMMERCIAL"))
      throw OaIntegrationException.invalid("BUSINESS_UNIT_DENIED", "调用方无当前报价业务单元权限");
    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType("WEAVER_OA");
    request.setSourceSystem(peer.sourceSystem());
    request.setExternalFormNo(requestId);
    request.setOaNo(formNo);
    // 正式需求仅接收一次；内部基线供核算与审批快照关联，不由 OA 传入或递增。
    request.setVersion("1");
    request.setHeader(header(main, process, sr, sc5, sc6));
    request.setExtraFields(extras(main, headers, "mainData"));
    request.setExtraFees(fees(main, headers, "mainData"));
    List<OaQuotationResponse.InputIssue> issues = new ArrayList<>();
    String divisionKey = sr ? "sssybdx" : sc5 ? "cpsyb" : "syb";
    missing(issues, main, divisionKey, "产品事业部", "BUSINESS_DIVISION_UNRESOLVED", null, null);
    missing(issues, main, sr ? "sqrq" : "sqsj", "申请日期", "SOURCE_FIELD_MISSING", null, null);
    if (value(main, "sqbm") == null && value(main, "sqcs") == null)
      missing(issues, main, "sqbm", "申请部门/处室", "SOURCE_FIELD_MISSING", null, null);
    String salesKey = sr ? "sftghwshxs" : "sftghwckfzdkh";
    if (sr || sc5 || sc6) {
      yesNo(main, salesKey, "mainData." + salesKey);
      missing(issues, main, salesKey, "海外销售方式", "SOURCE_FIELD_MISSING", null, null);
    }
    JsonNode rows = root.path("detailData");
    if (!rows.isArray() || rows.isEmpty() || rows.size() > 1000)
      fail("detailData", "有效产品明细须为1至1000行");
    List<QuoteIngestItemRequest> items = new ArrayList<>();
    List<SourceLine> lines = new ArrayList<>();
    Set<String> identities = new HashSet<>();
    Set<Integer> orders = new HashSet<>();
    String expectedTable = sr && !batch ? "明细表2" : "明细表1";
    for (int i = 0; i < rows.size(); i++) {
      JsonNode row = rows.get(i);
      String path = "detailData[" + i + "]";
      object(row, path, Set.of("tableKey", "rowId", "rowIndex", "fields"));
      String table = text(row, "tableKey", 64), rowId = text(row, "rowId", 128);
      if (!expectedTable.equals(table)) fail(path + ".tableKey", "当前类型应使用" + expectedTable);
      long ordinal = positive(row.path("rowIndex"), path + ".rowIndex");
      if (ordinal > 1000 || !orders.add((int) ordinal))
        fail(path + ".rowIndex", "行序号须为1至1000且不能重复");
      String externalId = codec.canonicalHash(List.of(table, rowId));
      if (!identities.add(externalId)) fail(path + ".rowId", "同一明细表的行标识不能重复");
      JsonNode f = row.path("fields");
      validateFields(f, details, path + ".fields");
      validateStorageValues(f, false, sr && batch, process, path + ".fields");
      QuoteIngestItemRequest item = item(f, main, process, business, sr, sc5, sc6, batch);
      item.setExternalLineId(externalId);
      item.setSeq((int) ordinal);
      item.setExtraFields(extras(f, details, path + ".fields"));
      item.getExtraFields().add(extra("OA_TABLE_KEY", "原明细表", table, "TEXT", path + ".tableKey"));
      item.getExtraFields().add(extra("OA_ROW_ID", "原明细行", rowId, "TEXT", path + ".rowId"));
      if (item.getAnnualVolume() != null)
        item.getExtraFields()
            .add(
                extra(
                    "ANNUAL_VOLUME_UNIT",
                    "预计年用量单位",
                    "TEN_THOUSAND_PIECES",
                    "TEXT",
                    path + ".fields"));
      item.setExtraFees(fees(f, details, path + ".fields"));
      if (item.getMaterialNo() == null
          && item.getSunlModel() == null
          && item.getCustomerDrawing() == null)
        issues.add(
            new OaQuotationResponse.InputIssue(
                path + ".fields.lh",
                table,
                rowId,
                "PRODUCT_UNRESOLVED",
                "BLOCKER",
                "BUSINESS_SOURCE",
                "料号、型号和客户图号均未提供，需补充产品识别资料"));
      items.add(item);
      lines.add(new SourceLine(table, rowId, externalId));
    }
    items.sort(Comparator.comparing(QuoteIngestItemRequest::getSeq));
    request.setItems(items);
    return new Mapped(
        requestId,
        new OaQuoteRequest(requestId, "COMMERCIAL", request),
        List.copyOf(lines),
        List.copyOf(issues));
  }

  private QuoteIngestHeaderRequest header(
      JsonNode m, String process, boolean sr, boolean sc5, boolean sc6) {
    QuoteIngestHeaderRequest h = new QuoteIngestHeaderRequest();
    h.setProcessCode(process);
    h.setBusinessUnitType("COMMERCIAL");
    h.setExpenseProductCategory(sr ? "家代商代销产品" : "商用直销产品");
    h.setApplyDate(date(value(m, sr ? "sqrq" : "sqsj"), "mainData." + (sr ? "sqrq" : "sqsj")));
    h.setApplicantName(value(m, "sqr"));
    h.setApplicantUnit(value(m, "sqdw"));
    h.setApplicantDept(value(m, "sqbm"));
    h.setApplicantOffice(value(m, "sqcs"));
    h.setSourceCompany(value(m, sc6 ? "ssgs" : "frzz"));
    h.setSourceBusinessDivision(value(m, sr ? "sssybdx" : sc5 ? "cpsyb" : "syb"));
    h.setCustomer(value(m, "khmc"));
    h.setProductAttr(value(m, sc6 ? "cpsx1" : "cpsx"));
    h.setPriceLinkMode(value(m, sr ? "xsjgldqk" : sc5 ? "tjldfs" : "xsjgsfld"));
    h.setOverseasSalesMode(value(m, sr ? "sftghwshxs" : "sftghwckfzdkh"));
    h.setTradeTerms(value(m, "mytk"));
    h.setExchangeRate(value(m, "hl"));
    h.setCopperPrice(value(m, sr ? "hsstjj" : sc5 ? "djtjg" : "hsstjjhs"));
    h.setZincPrice(value(m, sr ? "hssxjjhs" : sc5 ? "djxjg" : "hssxjj"));
    h.setAluminumPrice(value(m, !sr && !sc5 && !sc6 ? "hssljj" : "hssljjhs"));
    h.setSus304Price(value(m, sc5 ? "hsssus304jjh" : "hsssus304jj0"));
    h.setSus316lPrice(value(m, sc5 ? "hsssus316jjh" : "hsssus316jj0"));
    if (sr) {
      h.setSteelPrice(value(m, "hssbxgjjhs"));
      h.setSilverPrice(value(m, "hssyjjhs"));
    }
    h.setBaseShipping(value(m, sr ? "jzhyf" : "hyfhsbz"));
    h.setRemark(value(m, "bz"));
    return h;
  }

  private QuoteIngestItemRequest item(
      JsonNode f,
      JsonNode m,
      String process,
      String business,
      boolean sr,
      boolean sc5,
      boolean sc6,
      boolean batch) {
    boolean sc20 = process.equals("FI-SC-020");
    QuoteIngestItemRequest v = new QuoteIngestItemRequest();
    v.setProductName(value(f, sc20 ? "pm" : "cpmc"));
    v.setMaterialNo(identity(value(f, "lh")));
    v.setSunlModel(identity(value(f, "shxh")));
    v.setSpec(value(f, "gg"));
    v.setBusinessType(business);
    v.setProductAttr(value(f, "cpxz"));
    v.setCustomerDrawing(identity(value(f, sr ? "khmc" : sc6 ? "khbm" : "__absent")));
    if (sc20) v.setCustomerCode(value(f, "khbm"));
    v.setPackageMethod(value(f, "bzfs"));
    v.setShippingFee(value(f, sc20 ? "ysf" : "ysfyz"));
    if (sr && batch) v.setSupportQty(value(f, "yjnylwz"));
    else v.setAnnualVolume(sc20 ? scale(value(f, "yjnylz"), -4) : value(f, "yjnylwz"));
    v.setFirstQuoteFlag(bool(m, "sfscbj"));
    v.setTechnicianName(value(m, "jsy"));
    if (sr) v.setOriginCountry(value(m, "qyg"));
    if (sc5) v.setCertificationRequired(bool(m, "sfyrzxq"));
    v.setTotalWithShip(
        value(f, sc5 ? "hyfzcbbhs" : sc20 || sr && !batch ? "hysfzcbbhs" : "zcbbhs"));
    v.setTotalNoShip(
        value(
            f, sc5 ? "zcbbhyf" : sc20 ? "bhysfzcbbhs" : sr ? batch ? "cb1" : "zcbbhs" : "zcb1bhs"));
    v.setMaterialCost(value(f, "zjclf"));
    v.setLaborCost(value(f, sc5 ? "gz" : "zjrgf"));
    v.setManufacturingCost(value(f, "zzfy"));
    v.setManagementCost(value(f, "qyglf"));
    String validity =
        value(f, sc6 ? "cbyxqy1" : sc20 ? "cbyxq1" : sr && !batch ? "xpjgyxq" : "cbyxq");
    if (validity != null) {
      String months = validity.replace("个月", "").replace("月", "");
      if (!Set.of("1", "3", "6").contains(months)) fail("detailData.fields", "成本有效期须为1、3、6个月");
      v.setValidMonth(months);
    }
    v.setValidDate(date(value(f, "cbsxrq"), "detailData.fields.cbsxrq"));
    if (sc20) {
      v.setSus304WeightG(value(f, "bxgsu304k"));
      v.setSus316WeightG(value(f, "bxgsu316k"));
      v.setCopperWeightG(value(f, "tzk"));
    }
    return v;
  }

  private List<QuoteExtraFieldRequest> extras(JsonNode node, List<Field> definitions, String path) {
    List<QuoteExtraFieldRequest> result = new ArrayList<>();
    for (Field f : definitions) {
      if (node.has(f.key()))
        result.add(
            extra(
                f.key(),
                f.label(),
                value(node, f.key()),
                f.number() ? "NUMBER" : "TEXT",
                path + "." + f.key()));
    }
    return result;
  }

  private QuoteExtraFieldRequest extra(
      String key, String label, String value, String type, String path) {
    QuoteExtraFieldRequest f = new QuoteExtraFieldRequest();
    f.setFieldCode(key);
    f.setFieldName(label);
    f.setFieldValue(value);
    f.setValueType(type);
    f.setSourceFieldName(label);
    f.setSourceFieldPath(path);
    return f;
  }

  private List<QuoteExtraFeeRequest> fees(JsonNode node, List<Field> definitions, String path) {
    List<QuoteExtraFeeRequest> out = new ArrayList<>();
    for (Field f : definitions) {
      if (!f.number() || value(node, f.key()) == null) continue;
      String label = f.label();
      String category =
          label.contains("认证费")
              ? "CERTIFICATION"
              : label.contains("设备费")
                  ? "EQUIPMENT"
                  : label.contains("刀具")
                      ? "CUTTER"
                      : label.contains("模具") ? "MOLD" : label.contains("工装") ? "TOOLING" : null;
      if (category == null) continue;
      QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
      fee.setFeeCode(f.key());
      fee.setFeeName(label);
      fee.setFeeCategory(category);
      fee.setAmount(value(node, f.key()));
      fee.setUnit(
          label.contains("万元")
              ? "万元"
              : label.contains("元/只") ? "元/只" : label.contains("元") ? "元" : null);
      fee.setRemark("OA原值；税口径和分摊范围以原表单为准");
      fee.setSourceFieldName(label);
      fee.setSourceFieldPath(path + "." + f.key());
      out.add(fee);
    }
    return out;
  }

  private void validateFields(JsonNode node, List<Field> definitions, String path) {
    object(
        node,
        path,
        definitions.stream().map(Field::key).collect(java.util.stream.Collectors.toSet()));
    for (Field f : definitions) {
      String at = path + "." + f.key();
      if (!node.has(f.key())) {
        if (!f.optional()) fail(at, "缺少" + f.label() + "字段；原单未填也须传null");
        else continue;
      }
      JsonNode value = node.get(f.key());
      if (value.isNull()) continue;
      if (f.number()) {
        if (!value.isNumber()) fail(at, f.label() + "须为JSON数值或null");
        BigDecimal n = value.decimalValue().stripTrailingZeros();
        if (Math.max(n.scale(), 0) > 6 || n.precision() - n.scale() > 12)
          fail(at, f.label() + "超出数据库数值精度（最多12位整数、6位小数）");
      } else if (!value.isTextual() || value.textValue().length() > 2000)
        fail(at, f.label() + "须为不超过2000字的单个字符串或null");
    }
  }

  /** 原文虽有独立留存，标准列也不能静默截断或四舍五入后进入核算。 */
  private static void validateStorageValues(
      JsonNode node, boolean header, boolean srBatch, String process, String path) {
    Set<String> prices =
        Set.of(
            "djtjg",
            "djxjg",
            "hsstjjhs",
            "hsstjj",
            "hssxjj",
            "hssxjjhs",
            "hssljj",
            "hssljjhs",
            "hsssus304jjh",
            "hsssus316jjh",
            "hsssus304jj0",
            "hsssus316jj0",
            "hssbxgjjhs",
            "hssyjjhs",
            "hyfhsbz",
            "jzhyf");
    Set<String> costs =
        Set.of(
            "ysfyz",
            "ysf",
            "hyfzcbbhs",
            "hysfzcbbhs",
            "zcbbhs",
            "zcbbhyf",
            "bhysfzcbbhs",
            "cb1",
            "zcb1bhs",
            "zjclf",
            "gz",
            "zjrgf",
            "zzfy",
            "qyglf");
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              JsonNode value = entry.getValue();
              if (value.isNumber()) {
                int scale = 6, integerDigits = 12;
                if (header && prices.contains(key)) {
                  scale = 2;
                  integerDigits = 10;
                }
                if (!header && (costs.contains(key) || srBatch && key.equals("yjnylwz"))) {
                  scale = 4;
                  integerDigits = 8;
                }
                // 只转万只会增加四位小数，保证目标 annual_volume(18,6) 可精确保存。
                if (!header && process.equals("FI-SC-020") && key.equals("yjnylz")) scale = 2;
                BigDecimal number = value.decimalValue().stripTrailingZeros();
                if (number.scale() > scale || number.precision() - number.scale() > integerDigits)
                  fail(
                      path + "." + key,
                      "超出核算数据列精度，最多" + integerDigits + "位整数、" + scale + "位小数；请核对原单数值");
              } else if (value.isTextual()) {
                int limit = 2000;
                if (header) {
                  if (Set.of(
                          "sqr", "sqdw", "sqbm", "sqcs", "ssgs", "frzz", "syb", "cpsyb", "sssybdx",
                          "cpsx", "cpsx1", "mytk", "jsy", "qyg")
                      .contains(key)) limit = 128;
                  if (key.equals("khmc")) limit = 255;
                  if (Set.of("xsjgldqk", "tjldfs", "xsjgsfld").contains(key)) limit = 64;
                  if (key.equals("bz")) limit = 500;
                } else {
                  if (Set.of("cpmc", "pm", "lh", "shxh", "gg", "khmc", "khbm").contains(key))
                    limit = 255;
                  if (Set.of("bzfs", "cpxz").contains(key)
                      || process.equals("FI-SC-020") && key.equals("khbm")) limit = 128;
                }
                if (value.textValue().length() > limit)
                  fail(path + "." + key, "超出核算数据列长度，最多" + limit + "字");
              }
            });
  }

  private static void object(JsonNode node, String path, Set<String> allowed) {
    if (!node.isObject()) fail(path, "须为JSON对象");
    node.fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k)) fail(path + "." + k, "不属于当前表单的接口字段");
            });
  }

  private static String text(JsonNode n, String key, int max) {
    JsonNode v = n.path(key);
    if (!v.isTextual()
        || v.textValue().isBlank()
        || v.textValue().length() > max
        || !v.textValue().equals(v.textValue().trim())
        || v.textValue().chars().anyMatch(Character::isISOControl))
      fail(key, "须为不含控制字符的非空字符串，最长" + max + "字");
    return v.textValue();
  }

  private static long positive(JsonNode n, String path) {
    if (!n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1) fail(path, "须为正整数");
    return n.longValue();
  }

  private static String value(JsonNode n, String k) {
    JsonNode v = n.path(k);
    return v.isNull() || v.isMissingNode() || v.asText().isBlank() ? null : v.asText().trim();
  }

  private static String identity(String s) {
    return "/".equals(s) ? null : s;
  }

  private static String scale(String n, int power) {
    return n == null ? null : new BigDecimal(n).scaleByPowerOfTen(power).toPlainString();
  }

  private static Boolean bool(JsonNode n, String k) {
    yesNo(n, k, "mainData." + k);
    return value(n, k) == null ? null : "是".equals(value(n, k));
  }

  private static void yesNo(JsonNode n, String k, String path) {
    String v = value(n, k);
    if (v != null && !Set.of("是", "否").contains(v)) fail(path, "须为是、否或null");
  }

  private static String date(String s, String path) {
    if (s == null) return null;
    try {
      if (s.length() == 16)
        return LocalDateTime.parse(
                s,
                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                    .withResolverStyle(ResolverStyle.STRICT))
            .toLocalDate()
            .toString();
      return LocalDate.parse(s).toString();
    } catch (RuntimeException ex) {
      fail(path, "日期格式须为YYYY-MM-DD或YYYY-MM-DD HH:mm");
      return null;
    }
  }

  private static List<Field> concat(List<Field> a, List<Field> b) {
    List<Field> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static void missing(
      List<OaQuotationResponse.InputIssue> issues,
      JsonNode n,
      String key,
      String label,
      String code,
      String table,
      String row) {
    if (value(n, key) == null)
      issues.add(
          new OaQuotationResponse.InputIssue(
              "mainData." + key,
              table,
              row,
              code,
              "BLOCKER",
              "BUSINESS_SOURCE",
              label + "未填写，请完善原单；不能用默认值代替"));
  }

  private static void fail(String field, String message) {
    throw new OaQuotationValidationException(field, message);
  }
}
