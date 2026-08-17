package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.QuotePriceDraftMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("QCBP-21 四类价格正式发布路由")
class FormalPriceDraftPublisherTest {
  @Mock JdbcTemplate jdbc;
  @Mock QuotePriceDraftRepository repository;
  @Mock QuotePriceDraftMapper draftMapper;
  @Mock RangePriceDraftFormalPublisher rangePublisher;

  private FormalPriceDraftPublisher publisher;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CollaborationScope scope = new CollaborationScope("COMMERCIAL", "210");
  private final CollaborationPrincipal finance = new CollaborationPrincipal(
      31L, "财务甲", Set.of(CollaborationRole.FINANCE_REVIEWER));

  @BeforeEach
  void setUp() {
    publisher = new FormalPriceDraftPublisher(
        jdbc, objectMapper, repository, draftMapper, rangePublisher);
  }

  @Test
  @DisplayName("价格路由使用短来源标识，完整发布批次不写入现有32位source字段")
  void routeSourceFitsLegacyColumnAndPublishBatchRemainsInCollaborationTrace() {
    assertThat(FormalPriceDraftPublisher.ROUTE_SOURCE)
        .isEqualTo("quote_collab")
        .hasSizeLessThanOrEqualTo(32);

    when(draftMapper.markPublished(anyLong(), anyInt(), anyString(), anyLong(), anyString(),
        anyString(), anyString(), anyLong(), anyString())).thenReturn(1);
    when(repository.findFields(anyLong(), eq(scope))).thenReturn(allFields());
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(101L);

    String fullBatchNo = "QCP_12345678901234567890123456789012";
    publisher.publish(draft(10L, "FIXED_PURCHASE"), scope, fullBatchNo, finance);

    verify(draftMapper).markPublished(eq(10L), eq(1), eq("lp_price_fixed_item"), eq(101L),
        eq(fullBatchNo), eq("COMMERCIAL"), eq("210"), eq(31L), eq("财务甲"));
    verify(jdbc).update(
        org.mockito.ArgumentMatchers.contains("business_unit_type=? AND period=?"),
        eq("MAT-10"), eq("COMMERCIAL"), eq("2026-08"));
    verify(jdbc).update(
        org.mockito.ArgumentMatchers.contains("INSERT INTO lp_material_price_type"),
        eq("MAT-10"), eq("COMMERCIAL"), eq("物料10"), eq("规格"), eq("型号"), eq("件"),
        eq("固定价"), eq("2026-08"), eq("quote_collab"), eq(LocalDate.of(2026, 8, 1)),
        org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void publishesFixedLinkedRangeAndSettlementIntoExistingFormalTables() {
    when(draftMapper.markPublished(anyLong(), anyInt(), anyString(), anyLong(), anyString(),
        anyString(), anyString(), anyLong(), anyString())).thenReturn(1);
    List<QuotePriceDraftField> fields = allFields();
    when(repository.findFields(anyLong(), eq(scope))).thenReturn(fields);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(101L);
    when(rangePublisher.publish(any(), eq(fields), anyString())).thenReturn(
        new RangePriceDraftFormalPublisher.Publication(
            "lp_price_range_item", 301L, List.of(301L, 302L), null, "RANGE-BATCH"));

    assertThat(publisher.publish(draft(1L, "FIXED_PURCHASE"), scope, "BATCH-F", finance))
        .extracting(FormalPriceDraftPublisher.Published::sourceTable)
        .isEqualTo("lp_price_fixed_item");
    assertThat(publisher.publish(draft(2L, "LINKED"), scope, "BATCH-L", finance))
        .extracting(FormalPriceDraftPublisher.Published::sourceTable)
        .isEqualTo("lp_price_linked_item");
    assertThat(publisher.publish(draft(3L, "RANGE"), scope, "BATCH-R", finance))
        .extracting(FormalPriceDraftPublisher.Published::sourceTable)
        .isEqualTo("lp_price_range_item");
    assertThat(publisher.publish(draft(4L, "SETTLE_FIXED"), scope, "BATCH-S", finance))
        .extracting(FormalPriceDraftPublisher.Published::sourceTable)
        .isEqualTo("lp_price_fixed_item");

    verify(rangePublisher).publish(any(), eq(fields), eq("BATCH-R-D3"));
    verify(draftMapper).markPublished(eq(3L), eq(1), eq("lp_price_range_item"), eq(301L),
        eq("BATCH-R"), eq("COMMERCIAL"), eq("210"), eq(31L), eq("财务甲"));
  }

  @Test
  void repeatedPublishedDraftReturnsOriginalFormalTraceWithoutWritingAgain() {
    QuotePriceDraft draft = draft(9L, "LINKED");
    draft.setDraftStatus("PUBLISHED");
    draft.setPublishedSourceTable("lp_price_linked_item");
    draft.setPublishedSourceId(99L);
    draft.setPublishBatchNo("ORIGINAL-BATCH");

    assertThat(publisher.publish(draft, scope, "RETRY-BATCH", finance))
        .isEqualTo(new FormalPriceDraftPublisher.Published(
            "lp_price_linked_item", 99L, "ORIGINAL-BATCH"));
    org.mockito.Mockito.verifyNoInteractions(repository, draftMapper, rangePublisher, jdbc);
  }

  private QuotePriceDraft draft(Long id, String type) {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setId(id);
    draft.setDraftVersion(1);
    draft.setMaterialCode("MAT-" + id);
    draft.setMaterialName("物料" + id);
    draft.setMaterialSpec("规格");
    draft.setMaterialModel("型号");
    draft.setBusinessUnitType("COMMERCIAL");
    draft.setOrgCode("210");
    draft.setPriceType(type);
    draft.setDraftStatus("APPROVED");
    draft.setValidationStatus("PASSED");
    draft.setUnit("件");
    draft.setTaxIncluded(1);
    draft.setEffectiveFrom(LocalDate.of(2026, 8, 1));
    return draft;
  }

  private List<QuotePriceDraftField> allFields() {
    List<QuotePriceDraftField> fields = new ArrayList<>();
    fields.add(field("COMMON", "MAIN", "PRICE", "10.00"));
    fields.add(field("COMMON", "MAIN", "BASE_SETTLE_PRICE", "20.00"));
    fields.add(field("COMMON", "MAIN", "MARKUP_RATIO", "0.05"));
    fields.add(field("FORMULA", "MAIN", "FORMULA_EXPR", "[Cu]*[net_weight]"));
    fields.add(field("FORMULA", "MAIN", "FORMULA_EXPR_CN", "铜价×净重"));
    fields.add(field("VARIABLE", "MAIN", "net_weight", "1.2"));
    fields.add(field("BINDING", "1", "TOKEN_NAME", "铜价"));
    fields.add(field("BINDING", "1", "FACTOR_CODE", "Cu"));
    return fields;
  }

  private QuotePriceDraftField field(String section, String row, String code, String value) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setSectionCode(section);
    field.setRowKey(row);
    field.setFieldCode(code);
    try {
      field.setTargetValueJson(objectMapper.writeValueAsString(value));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    return field;
  }
}
