package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.QuoteCollaborationApprovedResultMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationGapMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import com.sanhua.marketingcost.mapper.QuotePriceDraftFieldMapper;
import com.sanhua.marketingcost.mapper.QuotePriceDraftMapper;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-02 协作持久化静态契约")
class QuoteCollaborationPersistenceContractTest {

  private static final Map<Class<?>, String> ENTITY_TABLES = entityTables();
  private static final Map<Class<?>, Class<?>> MAPPER_ENTITIES = mapperEntities();
  private static final String MIGRATION = readMigration(
      "/db/V206__quote_bom_price_collaboration_schema.sql");
  private static final String GAP_TRACE_MIGRATION = readMigration(
      "/db/V210__quote_collaboration_gap_trace_fields.sql");

  @Test
  @DisplayName("九个当前协作Entity与V206表名和全部字段一一对应")
  void entitiesMatchEveryMigrationColumn() {
    assertThat(ENTITY_TABLES).hasSize(9);
    ENTITY_TABLES.forEach((entityType, tableName) -> {
      TableName annotation = entityType.getAnnotation(TableName.class);
      assertThat(annotation).as(entityType.getSimpleName()).isNotNull();
      assertThat(annotation.value()).isEqualTo(tableName);
      assertThat(entityColumns(entityType))
          .as(tableName)
          .containsExactlyInAnyOrderElementsOf(migrationColumns(tableName));
    });
  }

  @Test
  @DisplayName("九个当前协作Mapper全部以正确Entity作为BaseMapper泛型")
  void mappersBindToCorrectEntity() {
    assertThat(MAPPER_ENTITIES).hasSize(9);
    MAPPER_ENTITIES.forEach((mapperType, entityType) -> {
      assertThat(mapperType.getInterfaces()).contains(BaseMapper.class);
      ParameterizedType generic = (ParameterizedType) mapperType.getGenericInterfaces()[0];
      assertThat(generic.getRawType()).isEqualTo(BaseMapper.class);
      assertThat(generic.getActualTypeArguments()).containsExactly(entityType);
    });
  }

  @Test
  @DisplayName("协作Mapper自定义SQL只访问当前九张新增表")
  void customMapperSqlNeverDependsOnLegacyTablesOrColumns() {
    Pattern tableReference = Pattern.compile(
        "(?i)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+([a-z0-9_]+)");
    Set<String> referencedTables = new LinkedHashSet<>();

    MAPPER_ENTITIES.keySet().forEach(mapperType -> {
      for (var method : mapperType.getDeclaredMethods()) {
        Select select = method.getAnnotation(Select.class);
        Update update = method.getAnnotation(Update.class);
        String sql = select != null ? String.join(" ", select.value())
            : update != null ? String.join(" ", update.value()) : "";
        var matcher = tableReference.matcher(sql);
        while (matcher.find()) {
          referencedTables.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        }
      }
    });

    assertThat(referencedTables).isNotEmpty();
    assertThat(referencedTables)
        .allMatch(new LinkedHashSet<>(ENTITY_TABLES.values())::contains);
  }

  @Test
  @DisplayName("数据库枚举码与冻结业务设计一致")
  void persistenceCodesStayStable() {
    assertThat(CollaborationCodes.codes(CollaborationCodes.MasterStatus.class))
        .containsExactly("WAIT_TECH", "WAIT_FINANCE", "PARTIAL_RETURN", "PUBLISHING",
            "PUBLISH_FAILED", "READY_FOR_COSTING", "COMPLETED", "CANCELLED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ProductTaskStatus.class))
        .containsExactly("WAIT_TECH", "BOM_IN_PROGRESS", "PACKAGE_IN_PROGRESS",
            "PRICE_IN_PROGRESS", "TECH_VALIDATION_FAILED", "TECH_SUBMITTED",
            "WAIT_FINANCE", "RETURNED_TO_TECH", "APPROVED_PUBLISHING",
            "PUBLISH_OR_REPRICE_FAILED", "READY_FOR_COSTING", "COSTING", "COMPLETED",
            "CANCELLED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.PriceType.class))
        .containsExactly("FIXED_PURCHASE", "LINKED", "RANGE", "SETTLE_FIXED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ProductForm.class))
        .containsExactly("NORMAL", "BARE", "UNKNOWN");
    assertThat(CollaborationCodes.codes(CollaborationCodes.PrimaryScope.class))
        .containsExactly("FULL_BOM", "BARE_PACKAGE", "PRICE_ONLY");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ValidationStatus.class))
        .containsExactly("NOT_CHECKED", "PASSED", "FAILED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.QuoteLinkType.class))
        .containsExactly("OWNER", "ACTIVE_TASK_LINK", "APPROVED_RESULT_REUSE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.QuoteLinkStatus.class))
        .containsExactly("WAIT_SOURCE", "RECHECKING", "READY", "FAILED", "CANCELLED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.GapCategory.class))
        .containsExactly("BOM", "PACKAGE", "PRICE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.MaterialRole.class))
        .containsExactly("NORMAL", "RAW", "SCRAP", "PACKAGE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.GapStatus.class))
        .containsExactly("OPEN", "DRAFT_READY", "RESOLVED", "WAIVED", "OBSOLETE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.DraftStatus.class))
        .containsExactly("EDITING", "VALIDATED", "SUBMITTED", "APPROVED", "REJECTED",
            "PUBLISHED", "VOIDED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.SourceMode.class))
        .containsExactly("COPY", "DIRECT");
    assertThat(CollaborationCodes.codes(CollaborationCodes.DraftFieldSection.class))
        .containsExactly("COMMON", "FORMULA", "VARIABLE", "RANGE_ROW");
    assertThat(CollaborationCodes.codes(CollaborationCodes.DraftFieldValueType.class))
        .containsExactly("TEXT", "DECIMAL", "DATE", "BOOLEAN", "JSON");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ReviewStatus.class))
        .containsExactly("PENDING", "PARTIAL", "REJECTED", "APPROVED", "PUBLISHING",
            "EFFECTIVE", "FAILED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ReviewItemType.class))
        .containsExactly("PRODUCT", "BOM", "PACKAGE", "PRICE_DRAFT");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ReviewDecision.class))
        .containsExactly("PENDING", "PASSED", "REJECTED");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ResultType.class))
        .containsExactly("FULL_BOM", "BARE_PACKAGE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ResultSourceObjectType.class))
        .containsExactly("SUPPLEMENT_VERSION", "PACKAGE_REFERENCE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ResultSourceSystem.class))
        .containsExactly("ELECTRONIC_DRAWING", "QUOTE_PACKAGE");
    assertThat(CollaborationCodes.codes(CollaborationCodes.ResultStatus.class))
        .containsExactly("ACTIVE", "EXPIRED", "REVOKED", "INVALIDATED");
  }

  @Test
  @DisplayName("六类业务编号具备固定前缀且批量生成不重复")
  void generatesStableUniqueBusinessNumbers() {
    CollaborationNumberGenerator generator = new UuidCollaborationNumberGenerator();
    Map<String, java.util.function.Supplier<String>> cases = Map.of(
        "QCT", generator::nextTaskNo,
        "QCPT", generator::nextProductTaskNo,
        "QCG", generator::nextGapNo,
        "QCPD", generator::nextPriceDraftNo,
        "QCR", generator::nextReviewNo,
        "QCAR", generator::nextApprovedResultNo);

    cases.forEach((prefix, supplier) -> {
      Set<String> values = new LinkedHashSet<>();
      for (int i = 0; i < 200; i++) {
        values.add(supplier.get());
      }
      assertThat(values).hasSize(200);
      assertThat(values).allMatch(value -> value.matches(prefix + "-\\d{8}-[0-9A-F]{20}"));
    });
  }

  private static Set<String> entityColumns(Class<?> entityType) {
    Set<String> columns = new LinkedHashSet<>();
    for (var field : entityType.getDeclaredFields()) {
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        columns.add(toSnakeCase(field.getName()));
      }
    }
    return columns;
  }

  private static Set<String> migrationColumns(String tableName) {
    Pattern tablePattern = Pattern.compile(
        "(?s)CREATE TABLE IF NOT EXISTS `" + Pattern.quote(tableName)
            + "` \\((.*?)\\) ENGINE=InnoDB");
    var tableMatcher = tablePattern.matcher(MIGRATION);
    assertThat(tableMatcher.find()).as(tableName).isTrue();
    Pattern columnPattern = Pattern.compile("(?m)^  `([^`]+)`\\s+");
    var columnMatcher = columnPattern.matcher(tableMatcher.group(1));
    Set<String> columns = new LinkedHashSet<>();
    while (columnMatcher.find()) {
      columns.add(columnMatcher.group(1));
    }
    if ("lp_quote_collaboration_gap".equals(tableName)) {
      Pattern addedColumnPattern = Pattern.compile("(?m)ADD COLUMN `([^`]+)`\\s+");
      var addedColumnMatcher = addedColumnPattern.matcher(GAP_TRACE_MIGRATION);
      while (addedColumnMatcher.find()) {
        columns.add(addedColumnMatcher.group(1));
      }
    }
    return columns;
  }

  private static String toSnakeCase(String value) {
    return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
  }

  private static String readMigration(String resource) {
    try (var in = QuoteCollaborationPersistenceContractTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("读取迁移失败：" + resource, ex);
    }
  }

  private static Map<Class<?>, String> entityTables() {
    Map<Class<?>, String> result = new LinkedHashMap<>();
    result.put(QuoteCollaborationTask.class, "lp_quote_collaboration_task");
    result.put(QuoteCollaborationProductTask.class, "lp_quote_collaboration_product_task");
    result.put(QuoteCollaborationQuoteLink.class, "lp_quote_collaboration_quote_link");
    result.put(QuoteCollaborationGap.class, "lp_quote_collaboration_gap");
    result.put(QuotePriceDraft.class, "lp_quote_price_draft");
    result.put(QuotePriceDraftField.class, "lp_quote_price_draft_field");
    result.put(QuoteCollaborationReview.class, "lp_quote_collaboration_review");
    result.put(QuoteCollaborationReviewItem.class, "lp_quote_collaboration_review_item");
    result.put(QuoteCollaborationApprovedResult.class, "lp_quote_collaboration_approved_result");
    return result;
  }

  private static Map<Class<?>, Class<?>> mapperEntities() {
    Map<Class<?>, Class<?>> result = new LinkedHashMap<>();
    result.put(QuoteCollaborationTaskMapper.class, QuoteCollaborationTask.class);
    result.put(QuoteCollaborationProductTaskMapper.class, QuoteCollaborationProductTask.class);
    result.put(QuoteCollaborationQuoteLinkMapper.class, QuoteCollaborationQuoteLink.class);
    result.put(QuoteCollaborationGapMapper.class, QuoteCollaborationGap.class);
    result.put(QuotePriceDraftMapper.class, QuotePriceDraft.class);
    result.put(QuotePriceDraftFieldMapper.class, QuotePriceDraftField.class);
    result.put(QuoteCollaborationReviewMapper.class, QuoteCollaborationReview.class);
    result.put(QuoteCollaborationReviewItemMapper.class, QuoteCollaborationReviewItem.class);
    result.put(QuoteCollaborationApprovedResultMapper.class,
        QuoteCollaborationApprovedResult.class);
    return result;
  }
}
