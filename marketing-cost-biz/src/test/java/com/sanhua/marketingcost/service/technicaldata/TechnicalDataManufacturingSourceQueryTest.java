package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.*;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TechnicalDataManufacturingSourceQueryTest {
  private final ElectronicDrawingSourceNodeRepository sources = mock(ElectronicDrawingSourceNodeRepository.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingU9SubBomPort u9 = mock(ElectronicDrawingU9SubBomPort.class);
  private final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  private final TechnicalDataManufacturingSourceQuery query = new TechnicalDataManufacturingSourceQuery(
      sources, materials, u9, new OaMessageCodec(new ObjectMapper().findAndRegisterModules()), versions);
  private final ElectronicDrawingWorkContext context = new ElectronicDrawingWorkContext(11L, 2, 21L, 31L,
      10L, 11L, "TASK", "OA", "TOP", null, null, null, null, null, "FULL_BOM", "2026-09",
      "210", "COMMERCIAL", "COMMERCIAL", "210", true, true, "EDITING", "MATERIALS_MATCHED", null, null, null);
  private QuoteBomSupplementVersion version;

  @BeforeEach void setup() {
    version = new QuoteBomSupplementVersion(); version.setId(31L); version.setOaFormItemId(11L);
    version.setPeriodMonth("2026-09"); version.setMaterialOrgCode("COMMERCIAL");
    version.setActiveFlag(1); version.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    version.setEffectiveFrom(LocalDate.of(2026, 9, 3));
    when(versions.selectById(31L)).thenReturn(version);
  }

  @Test void existingMissingAndUnmatchedUseActualNodesAndQueryDuplicateMaterialsOnce() {
    when(sources.findByVersionId(31L)).thenReturn(List.of(node(1, "A", null), node(2, "B", null), node(3, null, null), node(4, "B", null)));
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("A"), material("B")));
    when(u9.query(any())).thenAnswer(call -> {
      SubBomQuery request = call.getArgument(0);
      assertThat(request.effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 3));
      assertThat(request.periodMonth()).isEqualTo("2026-09");
      return "A".equals(request.parentMaterialCode()) ? available("A") : SubBomResult.failure(Status.NOT_FOUND, "B", "无下级");
    });
    var result = query.inspect(context);
    assertThat(result.nodes()).extracting(row -> row.state().name()).containsExactly("U9_READY", "MISSING_RAW", "WAIT_FINANCE", "MISSING_RAW");
    assertThat(result.nodes()).extracting(row -> row.sourceNodeId()).containsExactly(1L, 2L, 3L, 4L);
    assertThat(result.nodes().get(1).sourceNetWeight()).isEqualByComparingTo("10");
    assertThat(result.nodes().get(1).quantityPerParent()).isEqualByComparingTo("2");
    assertThat(result.nodes().get(1).sourceNetWeightUnit()).isEqualTo("g");
    assertThat(result.hasMissing()).isTrue(); assertThat(result.hasUnresolved()).isTrue();
    verify(u9, times(2)).query(any());
  }

  @Test void u9ReplacementDoesNotCreateFalseMissingTasksForDiscardedDrawingDescendants() {
    when(sources.findByVersionId(31L)).thenReturn(List.of(node(1, "A", null), node(2, null, "1")));
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("A")));
    when(u9.query(any())).thenReturn(available("A"));
    var result = query.inspect(context);
    assertThat(result.nodes()).hasSize(1); assertThat(result.hasUnresolved()).isFalse();
    assertThat(result.hasMissing()).isFalse();
  }

  @Test void timeoutAndWrongMonthNeverBecomeMissingRawRelationships() {
    when(sources.findByVersionId(31L)).thenReturn(List.of(node(1, "A", null)));
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("A")));
    when(u9.query(any())).thenReturn(SubBomResult.failure(Status.TIMEOUT, "A", "接口超时"));
    var result = query.inspect(context);
    assertThat(result.hasMissing()).isFalse(); assertThat(result.hasUnresolved()).isTrue();
    assertThat(result.nodes().getFirst().message()).contains("超时");
    version.setPeriodMonth("2026-08"); clearInvocations(u9);
    assertThat(query.inspect(context).unavailableReason()).contains("月份");
    verifyNoInteractions(u9);
  }

  @Test void numericScaleChangesAfterRestartPreserveApprovalButDifferentWeightDoesNot() {
    var row = node(1, "A", null);
    row.setReferenceWeight(new BigDecimal("10.0"));
    when(sources.findByVersionId(31L)).thenReturn(List.of(row));
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("A")));
    when(u9.query(any())).thenReturn(SubBomResult.failure(Status.NOT_FOUND, "A", "无下级"));
    var original = query.inspect(context);
    var codec = new OaMessageCodec(new ObjectMapper().findAndRegisterModules());
    String oldFingerprint = codec.canonicalHash(new TechnicalDataManufacturingSourceQuery.Assessment(
        original.sourceVersionId(), original.accountingMonth(), null, original.nodes(), original.unavailableReason()));
    row.setReferenceWeight(new BigDecimal("1E+1"));
    var restarted = query.inspect(context);
    assertThat(restarted.fingerprint()).isEqualTo(original.fingerprint());
    assertThat(query.matchesFingerprint(restarted, oldFingerprint)).isTrue();
    row.setReferenceWeight(new BigDecimal("11"));
    assertThat(query.matchesFingerprint(query.inspect(context), oldFingerprint)).isFalse();
  }

  private ElectronicDrawingSourceNode node(long id, String code, String parent) {
    var row = new ElectronicDrawingSourceNode(); row.setId(id); row.setSupplementVersionId(31L);
    row.setSourceSequence(Long.toString(id)); row.setParentSourceSequence(parent); row.setSourceRowNo((int) id + 1);
    row.setSourceName("接管" + id); row.setDrawingCode("DRAW"); row.setMaterial("铜"); row.setQty(new BigDecimal("2"));
    row.setResolvedMaterialCode(code); row.setMatchStatus(code == null ? "UNMATCHED" : "MANUALLY_SELECTED");
    row.setReferenceWeight(BigDecimal.TEN); row.setReferenceWeightUnit("g"); return row;
  }
  private MaterialMasterRaw material(String code) {
    var row = new MaterialMasterRaw(); row.setMaterialCode(code); row.setShapeAttr("制造件"); row.setUnit("只"); return row;
  }
  private SubBomResult available(String parent) {
    return SubBomResult.available(parent, "210", "COMMERCIAL", List.of(new U9Node("RAW:1", null, 55L, null,
        "RAW", "铜管", null, null, "RAW-D", "采购件", null, null, null, "主制造", "V1",
        new BigDecimal("0.012"), BigDecimal.ONE, "kg", 1)));
  }
}
