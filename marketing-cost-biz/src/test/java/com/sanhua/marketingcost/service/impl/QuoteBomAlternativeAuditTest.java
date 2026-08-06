package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeAuditServiceImpl;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomAlternativeAuditTest {

  @Test
  void writesOldAndNewSelectionVersionOperatorTimeAndRemark()
      throws Exception {
    SysOperationLogMapper mapper = mock(SysOperationLogMapper.class);
    when(mapper.insert(any(SysOperationLog.class))).thenReturn(1);
    ObjectMapper objectMapper = new ObjectMapper();
    Clock clock =
        Clock.fixed(
            Instant.parse("2026-07-30T02:30:00Z"),
            ZoneId.of("Asia/Shanghai"));
    QuoteBomAlternativeAuditServiceImpl service =
        new QuoteBomAlternativeAuditServiceImpl(
            mapper, objectMapper, clock);

    service.recordSelectionChange(
        scope(),
        "GROUP-1",
        beforeSelection(),
        new QuoteBomAlternativeSelectionResult(
            "SEL-2",
            "GROUP-1",
            "STD",
            "ALT",
            BomChildType.ALTERNATIVE,
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE,
            2,
            QuoteBomAlternativeSelection.STATUS_ACTIVE,
            false,
            false,
            true,
            "IMPORT-2",
            "BUILD-2"),
        "quoter",
        "本次报价选替代件");

    ArgumentCaptor<SysOperationLog> captor =
        ArgumentCaptor.forClass(SysOperationLog.class);
    verify(mapper).insert(captor.capture());
    SysOperationLog log = captor.getValue();
    assertThat(log.getTitle()).isEqualTo("报价BOM标准/替代选择");
    assertThat(log.getOperName()).isEqualTo("quoter");
    assertThat(log.getOperTime())
        .isEqualTo(LocalDateTime.of(2026, 7, 30, 10, 30));
    assertThat(log.getTargetId()).isEqualTo("GROUP-1");
    assertThat(log.getBusinessUnitType()).isEqualTo("COMMERCIAL");

    JsonNode before = objectMapper.readTree(log.getBeforeData());
    JsonNode after = objectMapper.readTree(log.getAfterData());
    JsonNode params = objectMapper.readTree(log.getOperParam());
    assertThat(before.get("selectedMaterialCode").asText())
        .isEqualTo("STD");
    assertThat(before.get("selectionVersion").asInt()).isEqualTo(1);
    assertThat(after.get("selectedMaterialCode").asText())
        .isEqualTo("ALT");
    assertThat(after.get("selectionVersion").asInt()).isEqualTo(2);
    assertThat(after.get("operator").asText()).isEqualTo("quoter");
    assertThat(after.get("operatedAt").asText())
        .isEqualTo("2026-07-30T10:30");
    assertThat(params.get("selectionRemark").asText())
        .isEqualTo("本次报价选替代件");
  }

  private QuoteBomAlternativeSelectionScope scope() {
    return new QuoteBomAlternativeSelectionScope(
        "OA-QBA-10",
        10L,
        "SOURCE-TOP",
        "2026-07",
        "210",
        "COMMERCIAL");
  }

  private QuoteBomAlternativeSelection beforeSelection() {
    QuoteBomAlternativeSelection row =
        new QuoteBomAlternativeSelection();
    row.setSelectionNo("SEL-1");
    row.setAlternativeGroupKey("GROUP-1");
    row.setStandardMaterialCode("STD");
    row.setSelectedMaterialCode("STD");
    row.setSelectedChildType(
        QuoteBomAlternativeSelection.CHILD_TYPE_STANDARD);
    row.setSelectionSource(
        QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    row.setSelectionVersion(1);
    row.setSelectionStatus(
        QuoteBomAlternativeSelection.STATUS_ACTIVE);
    row.setSourceImportBatchId("IMPORT-1");
    row.setSourceBuildBatchId("BUILD-1");
    row.setSelectedBy("system");
    row.setSelectedAt(LocalDateTime.of(2026, 7, 30, 9, 0));
    row.setSelectionRemark("系统默认标准件");
    return row;
  }
}
