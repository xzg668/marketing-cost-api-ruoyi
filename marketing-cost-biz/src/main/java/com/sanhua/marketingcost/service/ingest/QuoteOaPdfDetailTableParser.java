package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

public class QuoteOaPdfDetailTableParser {
  private static final String TABLE_FEE = "lp_oa_form_extra_fee";

  public void parse(QuotePdfParseContext context, QuoteIngestRequest request) {
    if (context == null || context.getDocument() == null || context.getTemplateDefinition() == null || request == null) {
      return;
    }
    QuoteOaPdfTableDefinition table = context.getTemplateDefinition().getItemTable();
    if (table == null) {
      return;
    }

    List<TableLine> lines = tableLines(context.getDocument());
    List<QuoteOaPdfFieldDefinition> fields = tableFields(context.getTemplateDefinition());
    TableRange range = tableRange(lines, table, fields);
    if (range == null || range.startIndex() + 1 >= range.endIndex()) {
      return;
    }

    List<Column> columns = inferColumns(lines.subList(range.startIndex() + 1, range.endIndex()), fields);
    if (columns.isEmpty()) {
      applyDenseFiSc006Fallback(context, request, lines, range);
      return;
    }

    int headerEndIndex = columns.stream().mapToInt(Column::lineIndex).max().orElse(range.startIndex());
    List<RowDraft> rows = readRows(lines, range, columns, headerEndIndex);
    for (RowDraft row : rows) {
      QuoteIngestItemRequest item = toItem(row, context.getTemplateDefinition());
      if (item == null) {
        continue;
      }
      request.getItems().add(item);
    }
    applyDenseFiSc006Fallback(context, request, lines, range);
  }

  private List<QuoteOaPdfFieldDefinition> tableFields(QuoteOaPdfTemplateDefinition template) {
    List<QuoteOaPdfFieldDefinition> fields = new ArrayList<>();
    fields.addAll(template.getItemFields());
    fields.addAll(template.getFeeFields());
    return fields;
  }

  private TableRange tableRange(
      List<TableLine> lines, QuoteOaPdfTableDefinition table, List<QuoteOaPdfFieldDefinition> fields) {
    int start = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (containsAny(lines.get(i).text(), table.getStartAnchors())) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      for (int i = 0; i < lines.size(); i++) {
        if (matchingHeaderColumnCount(lines.get(i), fields) >= 3) {
          start = Math.max(0, i - 1);
          break;
        }
      }
    }
    if (start < 0) {
      return null;
    }
    int end = lines.size();
    for (int i = start + 1; i < lines.size(); i++) {
      if (containsAny(lines.get(i).text(), table.getEndAnchors())) {
        end = i;
        break;
      }
    }
    return new TableRange(start, end);
  }

  private int matchingHeaderColumnCount(TableLine line, List<QuoteOaPdfFieldDefinition> fields) {
    Set<String> matched = new LinkedHashSet<>();
    for (QuotePdfToken token : line.tokens()) {
      QuoteOaPdfFieldDefinition field = matchField(token.getText(), fields);
      if (field != null) {
        matched.add(field.getFieldCode());
      }
    }
    return matched.size();
  }

  private List<Column> inferColumns(List<TableLine> lines, List<QuoteOaPdfFieldDefinition> fields) {
    Map<String, Column> byFieldCode = new LinkedHashMap<>();
    for (TableLine line : lines) {
      for (QuotePdfToken token : line.tokens()) {
        String text = trimToNull(token.getText());
        if (text == null) {
          continue;
        }
        QuoteOaPdfFieldDefinition field = matchField(text, fields);
        if (field == null || byFieldCode.containsKey(field.getFieldCode())) {
          continue;
        }
        byFieldCode.put(field.getFieldCode(), new Column(field, token.getX(), token.getWidth(), line.index()));
      }
    }
    List<Column> columns = new ArrayList<>(byFieldCode.values());
    columns.sort(Comparator.comparing(Column::x));
    return columns;
  }

  private List<RowDraft> readRows(
      List<TableLine> lines, TableRange range, List<Column> columns, int headerEndIndex) {
    List<RowDraft> rows = new ArrayList<>();
    RowDraft current = null;
    for (int i = headerEndIndex + 1; i < range.endIndex(); i++) {
      TableLine line = lines.get(i);
      if (!StringUtils.hasText(line.text()) || line.text().contains("暂无数据")) {
        continue;
      }
      Map<Column, String> values = valuesByColumn(line, columns);
      if (values.isEmpty() || isHeaderLike(values)) {
        continue;
      }
      String seq = values.entrySet().stream()
          .filter(entry -> "seq".equals(entry.getKey().field().getFieldCode()))
          .map(Map.Entry::getValue)
          .findFirst()
          .orElse(null);
      if (parseSeq(seq) != null) {
        current = new RowDraft();
        rows.add(current);
      } else if (current == null) {
        continue;
      }
      for (Map.Entry<Column, String> entry : values.entrySet()) {
        String fieldCode = entry.getKey().field().getFieldCode();
        if ("seq".equals(fieldCode) && parseSeq(entry.getValue()) == null) {
          continue;
        }
        current.append(entry.getKey().field(), entry.getValue(), sourcePath(line, entry.getKey().field()));
      }
    }
    return rows;
  }

  private Map<Column, String> valuesByColumn(TableLine line, List<Column> columns) {
    Map<Column, List<QuotePdfToken>> tokensByColumn = new LinkedHashMap<>();
    for (QuotePdfToken token : line.tokens()) {
      Column column = columnForToken(token, columns);
      if (column != null) {
        tokensByColumn.computeIfAbsent(column, ignored -> new ArrayList<>()).add(token);
      }
    }
    Map<Column, String> values = new LinkedHashMap<>();
    for (Map.Entry<Column, List<QuotePdfToken>> entry : tokensByColumn.entrySet()) {
      String value = joinTokens(entry.getValue());
      if (StringUtils.hasText(value)) {
        values.put(entry.getKey(), value);
      }
    }
    return values;
  }

  private Column columnForToken(QuotePdfToken token, List<Column> columns) {
    if (token == null || columns.isEmpty()) {
      return null;
    }
    float center = token.getX() + token.getWidth() / 2f;
    for (int i = 0; i < columns.size(); i++) {
      float left =
          i == 0 ? Float.NEGATIVE_INFINITY : midpoint(columns.get(i - 1), columns.get(i));
      float right =
          i == columns.size() - 1 ? Float.POSITIVE_INFINITY : midpoint(columns.get(i), columns.get(i + 1));
      if (center >= left && center < right) {
        return columns.get(i);
      }
    }
    return columns.get(columns.size() - 1);
  }

  private float midpoint(Column left, Column right) {
    return (left.x() + right.x()) / 2f;
  }

  private QuoteIngestItemRequest toItem(RowDraft row, QuoteOaPdfTemplateDefinition template) {
    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    for (Map.Entry<String, CellValue> entry : row.valuesByFieldCode().entrySet()) {
      CellValue cell = entry.getValue();
      if (!StringUtils.hasText(cell.value())) {
        continue;
      }
      if (isFeeField(cell.field())) {
        item.getExtraFees().add(toFee(cell.field(), cell.value(), cell.sourcePath()));
      } else if (!QuoteIngestFieldBinder.applyItemField(item, cell.field().getFieldCode(), cell.value())) {
        item.getExtraFields().add(toExtraField(cell.field(), cell.value(), cell.sourcePath()));
      }
    }
    if (!StringUtils.hasText(item.getBusinessType()) && StringUtils.hasText(template.getDefaultBusinessType())) {
      item.setBusinessType(template.getDefaultBusinessType());
    }
    if (item.getSeq() != null) {
      item.setExternalLineId("PDF:item:" + item.getSeq());
    }
    return isBlankItem(item) ? null : item;
  }

  private List<QuoteIngestItemRequest> readDenseFiSc006Rows(
      QuotePdfParseContext context, List<TableLine> lines, TableRange range) {
    if (!supportsDenseDetailFallback(context.getTemplateDefinition().getTemplateType())) {
      return List.of();
    }
    DenseLayout layout = inferDenseLayout(lines, range);
    if (layout == null || !layout.hasRequiredColumns()) {
      return List.of();
    }
    List<RowStart> starts = denseRowStarts(lines, range, layout);
    if (starts.isEmpty()) {
      return List.of();
    }
    boolean wideRowBand = context.getTemplateDefinition().getTemplateType() == QuoteExcelTemplateType.FI_SC_020;
    List<QuoteIngestItemRequest> items = new ArrayList<>();
    for (int i = 0; i < starts.size(); i++) {
      RowStart start = starts.get(i);
      float fromY = start.y() - (wideRowBand ? 42.0f : 24.0f);
      boolean previousRowOnSamePage = i > 0 && starts.get(i - 1).pageIndex() == start.pageIndex();
      if (wideRowBand && previousRowOnSamePage) {
        fromY = Math.max(fromY, starts.get(i - 1).y() + 18.0f);
      }
      boolean nextRowOnSamePage = i + 1 < starts.size() && starts.get(i + 1).pageIndex() == start.pageIndex();
      float toY = nextRowOnSamePage ? starts.get(i + 1).y() - 18.0f : start.y() + 48.0f;
      List<QuotePdfToken> rowTokens = denseRowTokens(lines, range, start.pageIndex(), fromY, toY);
      QuoteIngestItemRequest item =
          toDenseFiSc006Item(rowTokens, context.getTemplateDefinition(), layout, start.seq());
      if (item != null) {
        items.add(item);
      }
    }
    return normalizeDenseFiSc006Items(items);
  }

  private boolean supportsDenseDetailFallback(QuoteExcelTemplateType templateType) {
    return templateType == QuoteExcelTemplateType.FI_SC_006
        || templateType == QuoteExcelTemplateType.FI_SC_020;
  }

  private List<QuoteIngestItemRequest> normalizeDenseFiSc006Items(List<QuoteIngestItemRequest> items) {
    List<QuoteIngestItemRequest> filtered = new ArrayList<>();
    for (QuoteIngestItemRequest item : items) {
      if (hasDenseProductIdentity(item)) {
        filtered.add(item);
      }
    }
    for (int i = 0; i < filtered.size(); i++) {
      QuoteIngestItemRequest item = filtered.get(i);
      item.setSeq(i + 1);
      item.setExternalLineId("PDF:item:" + item.getSeq());
    }
    return filtered;
  }

  private boolean hasDenseProductIdentity(QuoteIngestItemRequest item) {
    if (item == null) {
      return false;
    }
    if (isLikelyMaterialCode(item.getMaterialNo())) {
      return true;
    }
    return StringUtils.hasText(item.getProductName())
        && StringUtils.hasText(item.getSunlModel())
        && (StringUtils.hasText(item.getSpec())
            || StringUtils.hasText(item.getAnnualVolume())
            || StringUtils.hasText(item.getTotalNoShip())
            || StringUtils.hasText(item.getTotalWithShip()));
  }

  private boolean isLikelyMaterialCode(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return false;
    }
    String compact = normalized.replaceAll("\\s+", "");
    return compact.length() >= 8 && compact.matches(".*\\d.*") && compact.matches("[A-Za-z0-9\\-]+");
  }

  private void applyDenseFiSc006Fallback(
      QuotePdfParseContext context, QuoteIngestRequest request, List<TableLine> lines, TableRange range) {
    List<QuoteIngestItemRequest> denseItems = readDenseFiSc006Rows(context, lines, range);
    if (denseItems.isEmpty()) {
      return;
    }
    boolean denseHasIdentity = denseItems.stream().allMatch(this::hasProductIdentity);
    if (!request.getItems().isEmpty() && !denseHasIdentity) {
      return;
    }
    if (!request.getItems().isEmpty()
        && request.getItems().stream().allMatch(this::hasProductKey)
        && request.getItems().stream().allMatch(this::hasProductIdentity)
        && request.getItems().size() > denseItems.size()) {
      return;
    }
    request.getItems().clear();
    request.getItems().addAll(denseItems);
  }

  private boolean hasMaterialNo(QuoteIngestItemRequest item) {
    return item != null && StringUtils.hasText(item.getMaterialNo());
  }

  private boolean hasProductKey(QuoteIngestItemRequest item) {
    return item != null && (StringUtils.hasText(item.getMaterialNo()) || StringUtils.hasText(item.getSunlModel()));
  }

  private boolean hasProductIdentity(QuoteIngestItemRequest item) {
    return item != null
        && StringUtils.hasText(item.getProductName())
        && StringUtils.hasText(item.getMaterialNo())
        && StringUtils.hasText(item.getSunlModel());
  }

  private DenseLayout inferDenseLayout(List<TableLine> lines, TableRange range) {
    Map<Float, List<QuotePdfToken>> tokensByColumn = new LinkedHashMap<>();
    for (int i = range.startIndex() + 1; i < range.endIndex(); i++) {
      for (QuotePdfToken token : lines.get(i).tokens()) {
        if (!isLikelyDenseHeaderToken(token.getText())) {
          continue;
        }
        float x = nearestColumnX(tokensByColumn.keySet(), token.getX());
        tokensByColumn.computeIfAbsent(x, ignored -> new ArrayList<>()).add(token);
      }
    }
    DenseLayout layout = new DenseLayout();
    for (Map.Entry<Float, List<QuotePdfToken>> entry : tokensByColumn.entrySet()) {
      layout.addColumnX(entry.getKey());
      List<QuotePdfToken> tokens = new ArrayList<>(entry.getValue());
      tokens.sort(Comparator.comparing(QuotePdfToken::getY));
      String label = normalizeLabel(joinRaw(tokens));
      String fieldCode = denseFieldCode(label);
      if (fieldCode != null) {
        layout.putCandidate(fieldCode, entry.getKey());
      }
    }
    layout.finish();
    return layout;
  }

  private float nearestColumnX(Set<Float> existingColumns, float x) {
    for (Float existing : existingColumns) {
      if (Math.abs(existing - x) <= 4.0f) {
        return existing;
      }
    }
    return x;
  }

  private boolean isLikelyDenseHeaderToken(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return false;
    }
    if (isNumberLike(normalized)) {
      return false;
    }
    String compact = normalizeLabel(normalized);
    if (isDenseHeaderFragment(normalized)) {
      return true;
    }
    return compact.contains("序产品")
        || compact.contains("号名称")
        || compact.contains("产品名称")
        || compact.contains("客户")
        || compact.contains("图号")
        || compact.contains("u11")
        || compact.contains("位码")
        || compact.contains("料号")
        || compact.contains("三花型")
        || compact.contains("规格")
        || compact.contains("运输")
        || compact.contains("年用量")
        || compact.contains("总成本")
        || compact.contains("总价")
        || compact.contains("成本有效")
        || compact.contains("有效期")
        || compact.contains("包装")
        || compact.contains("备注")
        || compact.contains("认证")
        || compact.contains("模具");
  }

  private String denseFieldCode(String normalizedLabel) {
    if (!StringUtils.hasText(normalizedLabel)) {
      return null;
    }
    if (normalizedLabel.contains("序号")
        || normalizedLabel.contains("序产品")
        || "序".equals(normalizedLabel)) {
      return "seq";
    }
    if (normalizedLabel.contains("产品名称")
        || normalizedLabel.contains("产品")
        || normalizedLabel.contains("品名")) {
      return "productName";
    }
    if (normalizedLabel.contains("客户图号")) {
      return "customerDrawing";
    }
    if (normalizedLabel.contains("u11位码") || normalizedLabel.contains("客户编码")) {
      return "customerCode";
    }
    if (normalizedLabel.contains("物料选择") || normalizedLabel.contains("料号")) {
      return "materialNo";
    }
    if (normalizedLabel.contains("三花型号") || normalizedLabel.contains("三花型")) {
      return "sunlModel";
    }
    if (normalizedLabel.contains("规格")) {
      return "spec";
    }
    // 部品 FI-SC-006 PDF 的单只费用列有时只抽出“费/[元/只]”，这里仅识别带单位的费用列。
    if (!normalizedLabel.contains("分摊")
        && (normalizedLabel.startsWith("费用元只") || normalizedLabel.startsWith("费元只"))) {
      return "shippingFee";
    }
    if (normalizedLabel.contains("总成本")
        && normalizedLabel.contains("不含税")
        && !normalizedLabel.contains("材料")) {
      return "totalNoShip";
    }
    if (normalizedLabel.contains("不含运输费总成本")
        || normalizedLabel.contains("不含运输费总成")
        || normalizedLabel.contains("不含运费总成本")
        || normalizedLabel.contains("不含运费总价")) {
      return "totalNoShip";
    }
    if (normalizedLabel.contains("含运输费总成本")
        || normalizedLabel.contains("含运输费总成")
        || normalizedLabel.contains("含运费总成本")
        || normalizedLabel.contains("含运费总价")) {
      return "totalWithShip";
    }
    if (normalizedLabel.contains("运输费") || normalizedLabel.contains("运费")) {
      return "shippingFee";
    }
    if (normalizedLabel.contains("年用量")
        || (normalizedLabel.contains("年用") && normalizedLabel.contains("量"))) {
      return "annualVolume";
    }
    if (normalizedLabel.contains("包装方式")) {
      return "packageMethod";
    }
    if (normalizedLabel.contains("成本有效期")
        || normalizedLabel.contains("有效期")
        || (normalizedLabel.contains("有效") && normalizedLabel.contains("月"))) {
      return "validMonth";
    }
    return null;
  }

  private List<RowStart> denseRowStarts(List<TableLine> lines, TableRange range, DenseLayout layout) {
    DenseColumn seqColumn = layout.column("seq");
    List<SeqFragment> fragments = new ArrayList<>();
    for (int i = range.startIndex() + 1; i < range.endIndex(); i++) {
      TableLine line = lines.get(i);
      for (QuotePdfToken token : line.tokens()) {
        if (!seqColumn.contains(token)) {
          continue;
        }
        if (parseSeqFragment(token.getText()) == null) {
          continue;
        }
        fragments.add(new SeqFragment(line.pageIndex(), token.getX(), token.getY(), token.getText()));
      }
    }
    fragments.sort(Comparator.comparing(SeqFragment::pageIndex).thenComparing(SeqFragment::y));

    List<RowStart> starts = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    SeqCluster cluster = null;
    for (SeqFragment fragment : fragments) {
      if (cluster == null || !cluster.canAppend(fragment)) {
        addSeqClusterStart(starts, seen, cluster);
        cluster = new SeqCluster(fragment);
      } else {
        cluster.append(fragment);
      }
    }
    addSeqClusterStart(starts, seen, cluster);
    starts.sort(Comparator.comparing(RowStart::pageIndex).thenComparing(RowStart::y));
    return starts;
  }

  private void addSeqClusterStart(List<RowStart> starts, Set<String> seen, SeqCluster cluster) {
    if (cluster == null) {
      return;
    }
    Integer seq = parseSeq(cluster.text());
    if (seq == null || seq <= 0) {
      return;
    }
    String key = cluster.pageIndex() + ":" + seq + ":" + Math.round(cluster.startY());
    if (seen.add(key)) {
      starts.add(new RowStart(cluster.pageIndex(), cluster.startY(), seq));
    }
  }

  private String parseSeqFragment(String value) {
    String normalized = trimToNull(value);
    if (normalized == null || !normalized.matches("\\d+")) {
      return null;
    }
    return normalized;
  }

  private List<QuotePdfToken> denseRowTokens(
      List<TableLine> lines, TableRange range, int pageIndex, float fromY, float toY) {
    List<QuotePdfToken> tokens = new ArrayList<>();
    for (int i = range.startIndex() + 1; i < range.endIndex(); i++) {
      TableLine line = lines.get(i);
      if (line.pageIndex() != pageIndex) {
        continue;
      }
      for (QuotePdfToken token : line.tokens()) {
        if (token.getY() >= fromY && token.getY() < toY) {
          tokens.add(token);
        }
      }
    }
    tokens.sort(Comparator.comparing(QuotePdfToken::getY).thenComparing(QuotePdfToken::getX));
    return tokens;
  }

  private QuoteIngestItemRequest toDenseFiSc006Item(
      List<QuotePdfToken> tokens, QuoteOaPdfTemplateDefinition template, DenseLayout layout, Integer seq) {
    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    item.setSeq(seq);
    item.setExternalLineId("PDF:item:" + seq);
    item.setProductName(joinDenseColumn(tokens, layout.column("productName"), false));
    if (!StringUtils.hasText(item.getProductName())) {
      item.setProductName(joinDenseTextColumn(tokens, layout.column("seq")));
    }
    item.setCustomerDrawing(joinDenseColumn(tokens, layout.column("customerDrawing"), false));
    item.setCustomerCode(joinDenseColumn(tokens, layout.column("customerCode"), false));
    item.setMaterialNo(joinDenseColumn(tokens, layout.column("materialNo"), false));
    item.setSunlModel(joinDenseColumn(tokens, layout.column("sunlModel"), false));
    item.setSpec(joinDenseColumn(tokens, layout.column("spec"), false));
    item.setShippingFee(joinDenseColumn(tokens, layout.column("shippingFee"), true));
    item.setAnnualVolume(joinDenseColumn(tokens, layout.column("annualVolume"), true));
    item.setTotalWithShip(joinDenseColumn(tokens, layout.column("totalWithShip"), true));
    item.setTotalNoShip(joinDenseColumn(tokens, layout.column("totalNoShip"), true));
    item.setValidMonth(joinDenseColumn(tokens, layout.column("validMonth"), true));
    item.setPackageMethod(joinDenseColumn(tokens, layout.column("packageMethod"), false));
    if (!StringUtils.hasText(item.getBusinessType()) && StringUtils.hasText(template.getDefaultBusinessType())) {
      item.setBusinessType(template.getDefaultBusinessType());
    }
    return hasMaterialNo(item) || StringUtils.hasText(item.getSunlModel()) ? item : null;
  }

  private String joinDenseColumn(List<QuotePdfToken> tokens, DenseColumn column, boolean numberOnly) {
    if (column == null) {
      return null;
    }
    StringBuilder value = new StringBuilder();
    for (QuotePdfToken token : tokens) {
      if (!column.contains(token) || isDenseHeaderFragment(token.getText())) {
        continue;
      }
      String text = trimToNull(token.getText());
      if (text == null) {
        continue;
      }
      if (numberOnly && !isNumberLike(text)) {
        continue;
      }
      value.append(text);
    }
    return trimToNull(value.toString());
  }

  private String joinDenseTextColumn(List<QuotePdfToken> tokens, DenseColumn column) {
    if (column == null) {
      return null;
    }
    StringBuilder value = new StringBuilder();
    for (QuotePdfToken token : tokens) {
      if (!column.contains(token) || isDenseHeaderFragment(token.getText())) {
        continue;
      }
      String text = trimToNull(token.getText());
      if (text == null || isNumberLike(text)) {
        continue;
      }
      value.append(text);
    }
    return trimToNull(value.toString());
  }

  private String joinRaw(List<QuotePdfToken> tokens) {
    StringBuilder value = new StringBuilder();
    for (QuotePdfToken token : tokens) {
      if (StringUtils.hasText(token.getText())) {
        value.append(token.getText());
      }
    }
    return value.toString();
  }

  private boolean isNumberLike(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return false;
    }
    return normalized.matches("-?\\d+(,\\d{3})*(\\.\\d*)?%?");
  }

  private boolean isDenseHeaderFragment(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return false;
    }
    String compact = normalizeLabel(normalized);
    if ("期月".equals(compact) || "月".equals(compact)) {
      return true;
    }
    if (compact.contains("预计年用")
        || compact.contains("量只")
        || compact.contains("总成")
        || compact.contains("费总成")) {
      return true;
    }
    if (Set.of(
            "客户图号",
            "客户编码",
            "u11位码",
            "物料选择",
            "产品名称",
            "品名",
            "包装方式",
            "运输费",
            "预计年用量",
            "年用量",
            "含运输费总成本",
            "不含运输费总成本",
            "含运费总价",
            "不含运费总价",
            "成本有效期",
            "有效期",
            "备注")
        .contains(compact)) {
      return true;
    }
    return Set.of(
            "序",
            "号",
            "产品",
            "名称",
            "客户",
            "图号",
            "U11",
            "位码",
            "料号",
            "三花型",
            "三花型号",
            "规格",
            "运",
            "输",
            "费",
            "含",
            "不含",
            "预",
            "计",
            "年",
            "用",
            "量",
            "成本",
            "有效",
            "期",
            "方式")
        .contains(normalized);
  }

  private boolean isFeeField(QuoteOaPdfFieldDefinition field) {
    return TABLE_FEE.equals(field.getTargetTable())
        || QuoteIngestFeeFieldClassifier.isFeeField(field.getFieldCode(), field.getFieldName());
  }

  private QuoteExtraFeeRequest toFee(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
    QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
    fee.setFeeCode(field.getFieldCode());
    fee.setFeeName(field.getFieldName());
    fee.setFeeCategory(QuoteIngestFeeFieldClassifier.feeCategory(field.getFieldCode(), field.getFieldName()));
    fee.setAmount(value);
    fee.setUnit(QuoteIngestFeeFieldClassifier.unit(field.getFieldName()));
    fee.setSourceFieldName(field.getFieldName());
    fee.setSourceFieldPath(sourcePath);
    return fee;
  }

  private QuoteExtraFieldRequest toExtraField(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
    QuoteExtraFieldRequest extraField = new QuoteExtraFieldRequest();
    extraField.setFieldCode(field.getFieldCode());
    extraField.setFieldName(field.getFieldName());
    extraField.setFieldValue(value);
    extraField.setValueType(valueType(value));
    extraField.setSourceFieldName(field.getFieldName());
    extraField.setSourceFieldPath(sourcePath);
    return extraField;
  }

  private boolean isBlankItem(QuoteIngestItemRequest item) {
    return item.getSeq() == null
        && !StringUtils.hasText(item.getMaterialNo())
        && !StringUtils.hasText(item.getSunlModel())
        && !StringUtils.hasText(item.getProductName())
        && !StringUtils.hasText(item.getCustomerCode());
  }

  private boolean isHeaderLike(Map<Column, String> values) {
    for (Map.Entry<Column, String> entry : values.entrySet()) {
      if (!isLabelMatch(entry.getValue(), entry.getKey().field())) {
        return false;
      }
    }
    return true;
  }

  private QuoteOaPdfFieldDefinition matchField(String text, List<QuoteOaPdfFieldDefinition> fields) {
    return fields.stream()
        .filter(field -> isLabelMatch(text, field))
        .sorted(
            Comparator.comparingInt((QuoteOaPdfFieldDefinition field) -> longestLabel(field).length()).reversed())
        .findFirst()
        .orElse(null);
  }

  private boolean isLabelMatch(String text, QuoteOaPdfFieldDefinition field) {
    String normalizedText = normalizeLabel(text);
    if (!StringUtils.hasText(normalizedText)) {
      return false;
    }
    for (String label : labels(field)) {
      String normalizedLabel = normalizeLabel(label);
      if (StringUtils.hasText(normalizedLabel)
          && (normalizedText.equals(normalizedLabel) || normalizedText.contains(normalizedLabel))) {
        return true;
      }
    }
    return false;
  }

  private List<String> labels(QuoteOaPdfFieldDefinition field) {
    Set<String> labels = new LinkedHashSet<>();
    labels.add(field.getFieldName());
    labels.addAll(field.getAliases());
    return labels.stream().filter(StringUtils::hasText).toList();
  }

  private String longestLabel(QuoteOaPdfFieldDefinition field) {
    return labels(field).stream().max(Comparator.comparingInt(String::length)).orElse("");
  }

  private List<TableLine> tableLines(QuotePdfDocument document) {
    if (document == null || document.getPages() == null) {
      return List.of();
    }
    List<TableLine> lines = new ArrayList<>();
    for (QuotePdfPage page : document.getPages()) {
      if (page == null || page.getLines() == null) {
        continue;
      }
      for (int i = 0; i < page.getLines().size(); i++) {
        QuotePdfLine line = page.getLines().get(i);
        if (line == null) {
          continue;
        }
        List<QuotePdfToken> tokens = line.getTokens() == null ? List.of() : new ArrayList<>(line.getTokens());
        tokens.sort(Comparator.comparing(QuotePdfToken::getX));
        lines.add(new TableLine(lines.size(), page.getPageIndex(), i, nullToEmpty(line.getText()), tokens));
      }
    }
    return lines;
  }

  private String joinTokens(List<QuotePdfToken> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return null;
    }
    List<QuotePdfToken> sorted = new ArrayList<>(tokens);
    sorted.sort(Comparator.comparing(QuotePdfToken::getX));
    StringBuilder builder = new StringBuilder();
    QuotePdfToken previous = null;
    for (QuotePdfToken token : sorted) {
      if (builder.length() > 0 && shouldInsertSpace(previous, token)) {
        builder.append(' ');
      }
      builder.append(token.getText());
      previous = token;
    }
    return trimToNull(builder.toString());
  }

  private boolean shouldInsertSpace(QuotePdfToken previous, QuotePdfToken current) {
    if (previous == null || current == null) {
      return false;
    }
    return current.getX() - (previous.getX() + previous.getWidth()) > 2.0f;
  }

  private String sourcePath(TableLine line, QuoteOaPdfFieldDefinition field) {
    return "PDF:page:" + (line.pageIndex() + 1) + ":line:" + (line.pageLineIndex() + 1) + ":" + field.getFieldName();
  }

  private boolean containsAny(String text, List<String> anchors) {
    if (!StringUtils.hasText(text) || anchors == null) {
      return false;
    }
    for (String anchor : anchors) {
      if (StringUtils.hasText(anchor) && text.contains(anchor)) {
        return true;
      }
    }
    return false;
  }

  private Integer parseSeq(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    if (!normalized.matches("\\d+(\\.0)?")) {
      return null;
    }
    try {
      return Integer.valueOf(normalized.replace(".0", ""));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String normalizeLabel(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return "";
    }
    return normalized
        .replaceAll("[\\s:：,，.。()（）/\\\\\\-—_\\[\\]【】]", "")
        .toLowerCase(Locale.ROOT);
  }

  private String valueType(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return "TEXT";
    }
    if (normalized.matches("-?\\d+(,\\d{3})*(\\.\\d+)?")) {
      return "NUMBER";
    }
    if (normalized.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}.*")) {
      return "DATE";
    }
    return "TEXT";
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record TableRange(int startIndex, int endIndex) {}

  private record TableLine(
      int index, int pageIndex, int pageLineIndex, String text, List<QuotePdfToken> tokens) {}

  private record Column(QuoteOaPdfFieldDefinition field, float x, float width, int lineIndex) {}

  private record CellValue(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {}

  private record RowStart(int pageIndex, float y, Integer seq) {}

  private record SeqFragment(int pageIndex, float x, float y, String text) {}

  private static final class SeqCluster {
    private static final float SAME_SEQ_X_TOLERANCE = 4.0f;
    private static final float SAME_SEQ_Y_GAP = 14.0f;

    private final int pageIndex;
    private final float x;
    private final float startY;
    private float endY;
    private final StringBuilder text = new StringBuilder();

    private SeqCluster(SeqFragment fragment) {
      this.pageIndex = fragment.pageIndex();
      this.x = fragment.x();
      this.startY = fragment.y();
      this.endY = fragment.y();
      this.text.append(fragment.text());
    }

    private boolean canAppend(SeqFragment fragment) {
      return fragment.pageIndex() == pageIndex
          && Math.abs(fragment.x() - x) <= SAME_SEQ_X_TOLERANCE
          && fragment.y() - endY <= SAME_SEQ_Y_GAP;
    }

    private void append(SeqFragment fragment) {
      text.append(fragment.text());
      endY = fragment.y();
    }

    private int pageIndex() {
      return pageIndex;
    }

    private float startY() {
      return startY;
    }

    private String text() {
      return text.toString();
    }
  }

  private static final class DenseLayout {
    private final Set<Float> columnXs = new LinkedHashSet<>();
    private final Map<String, Float> xByFieldCode = new LinkedHashMap<>();
    private final Map<String, DenseColumn> columnsByFieldCode = new LinkedHashMap<>();

    private void addColumnX(float x) {
      columnXs.add(x);
    }

    private void put(String fieldCode, float x) {
      xByFieldCode.put(fieldCode, x);
    }

    private void putCandidate(String fieldCode, float x) {
      if ("shippingFee".equals(fieldCode)) {
        Float current = xByFieldCode.get(fieldCode);
        if (current == null || x < current) {
          put(fieldCode, x);
        }
        return;
      }
      if (!xByFieldCode.containsKey(fieldCode)) {
        put(fieldCode, x);
      }
    }

    private void finish() {
      List<Float> sorted = new ArrayList<>(columnXs);
      sorted.sort(Float::compare);
      for (Map.Entry<String, Float> entry : xByFieldCode.entrySet()) {
        int index = sorted.indexOf(entry.getValue());
        if (index < 0) {
          continue;
        }
        float left = index == 0 ? Float.NEGATIVE_INFINITY : (sorted.get(index - 1) + sorted.get(index)) / 2f;
        float right =
            index == sorted.size() - 1 ? Float.POSITIVE_INFINITY : (sorted.get(index) + sorted.get(index + 1)) / 2f;
        columnsByFieldCode.put(entry.getKey(), new DenseColumn(left, right));
      }
    }

    private boolean hasRequiredColumns() {
      return columnsByFieldCode.containsKey("seq")
          && (columnsByFieldCode.containsKey("materialNo") || columnsByFieldCode.containsKey("sunlModel"));
    }

    private DenseColumn column(String fieldCode) {
      return columnsByFieldCode.get(fieldCode);
    }

    private Map<String, DenseColumn> columnsByFieldCode() {
      return columnsByFieldCode;
    }
  }

  private record DenseColumn(float leftInclusive, float rightExclusive) {
    private boolean contains(QuotePdfToken token) {
      return token != null && token.getX() >= leftInclusive && token.getX() < rightExclusive;
    }
  }

  private static final class RowDraft {
    private final Map<String, CellValue> valuesByFieldCode = new LinkedHashMap<>();

    private void append(QuoteOaPdfFieldDefinition field, String value, String sourcePath) {
      if (!StringUtils.hasText(value)) {
        return;
      }
      valuesByFieldCode.compute(
          field.getFieldCode(),
          (ignored, existing) -> {
            if (existing == null || !StringUtils.hasText(existing.value())) {
              return new CellValue(field, value.trim(), sourcePath);
            }
            if ("seq".equals(field.getFieldCode())) {
              return existing;
            }
            return new CellValue(field, existing.value() + " " + value.trim(), existing.sourcePath());
          });
    }

    private Map<String, CellValue> valuesByFieldCode() {
      return valuesByFieldCode;
    }
  }
}
