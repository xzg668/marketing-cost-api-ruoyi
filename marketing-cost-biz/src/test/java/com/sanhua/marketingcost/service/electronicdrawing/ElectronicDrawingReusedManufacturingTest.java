package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElectronicDrawingReusedManufacturingTest {
  final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  final QuoteBomSupplementDetailMapper details = mock(QuoteBomSupplementDetailMapper.class);
  final ElectronicDrawingSourceNodeRepository nodes = mock(ElectronicDrawingSourceNodeRepository.class);
  final QuoteTechDataVersionMapper technical = mock(QuoteTechDataVersionMapper.class);
  final TechnicalDataVersionContentCodec codec = mock(TechnicalDataVersionContentCodec.class);
  final ElectronicDrawingWorkflowContextPort contexts = mock(ElectronicDrawingWorkflowContextPort.class);
  final com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope reprice = mock(com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope.class);
  final com.sanhua.marketingcost.service.technicaldata.ElectronicDrawingManufacturingInputs finance = mock(com.sanhua.marketingcost.service.technicaldata.ElectronicDrawingManufacturingInputs.class);
  final ElectronicDrawingReusedManufacturing service = new ElectronicDrawingReusedManufacturing(versions, details, nodes, technical, codec, contexts, reprice, finance);
  final ElectronicDrawingWorkContext context = new ElectronicDrawingWorkContext(2L, 1, 2L, 20L, 2L, 2L,
      "TASK", "OA", "P", null, "Product", null, null, "NON_BARE", "FULL_BOM", "2026-11",
      "210", "COMMERCIAL", "COMMERCIAL", "210", true, true, "NEED_TECH", null, null, null, null);
  QuoteBomSupplementVersion current;
  ElectronicDrawingSourceNode copied;
  QuoteBomSupplementDetail raw;
  RawMaterial input;

  @BeforeEach void fixture() {
    var old = version(10L, null, 1L, "APPROVED");
    current = version(20L, 10L, 2L, "DRAFT");
    when(versions.selectById(10L)).thenReturn(old); when(versions.selectById(20L)).thenReturn(current);
    var original = node(7L, null); copied = node(17L, 7L);
    when(nodes.findByVersionId(10L)).thenReturn(List.of(original)); when(nodes.findByVersionId(20L)).thenReturn(List.of(copied));
    var parent = detail("/P/ED:7/", 1, "MAKE", "E_DRAWING", "2", "2", 7L);
    raw = detail("/P/ED:7/TECH:83:RAW:7/", 2, "RAW", "TECH_RAW", ".015", ".030", 7L);
    when(details.selectList(any())).thenReturn(List.of(parent, raw));
    var frozen = new QuoteTechDataVersion(); frozen.setId(83L); frozen.setVersionStatus("SUBMITTED");
    when(technical.selectById(83L)).thenReturn(frozen);
    input = new RawMaterial("RAW:7", "7", 10L, "MAKE", "RAW", "DRAW-R",
        new BigDecimal(".010"), new BigDecimal(".015"), "kg", null, new BigDecimal(".015"), new BigDecimal("120"),
        new ManufacturingNodeEvidence("制造件", "DRAW-M", new BigDecimal("2"), new BigDecimal("10"), "g", "铜棒", null, "NO_SCRAP_CONFIRMED", List.of()));
    when(codec.manufacturing(frozen)).thenReturn(new Manufacturing(List.of(input), null));
    when(contexts.loadForCurrentBusinessUnit(2L, "2026-11")).thenReturn(context);
    when(reprice.costingDrawing(eq(2L), eq("2026-11"), any())).thenReturn(context);
  }

  @Test void preservesOriginalWeightsLengthAndQuantityForTheUnchangedManufacturingNode() {
    var result = service.read(context);
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().nodeId()).isEqualTo(17L);
    assertThat(result.getFirst().technicalVersionId()).isEqualTo(83L);
    assertThat(result.getFirst().input()).isSameAs(input);
    assertThat(result.getFirst().input().grossWeightKg()).isEqualByComparingTo(".015");
    assertThat(result.getFirst().input().netLengthMm()).isEqualByComparingTo("120");
  }

  @Test void newOrChangedNodeCannotBorrowOldRawMaterial() {
    copied.setCorrectedFromSourceNodeId(null);
    assertThat(service.read(context)).isEmpty();
    copied.setCorrectedFromSourceNodeId(7L); copied.setResolvedMaterialCode("OTHER-MAKE");
    assertThat(service.read(context)).isEmpty();
  }

  @Test void changedWeightCannotBorrowOldManufacturingInputs() {
    copied.setReferenceWeight(new BigDecimal("20"));
    assertThat(service.read(context)).isEmpty();
  }

  @Test void inconsistentCurrentTotalCannotBeUsedForCosting() {
    var parent = detail("/P/ED:17/", 1, "MAKE", "E_DRAWING", "3", "3", 17L);
    var nextRaw = detail("/P/ED:17/TECH:83:RAW:7/", 2, "RAW", "TECH_RAW", ".015", ".030", 17L);
    when(details.selectList(any())).thenReturn(List.of(detail("/P/ED:7/", 1, "MAKE", "E_DRAWING", "2", "2", 7L), raw), List.of(parent, nextRaw));
    assertThatThrownBy(() -> service.costingInputs(2L, "2026-11")).hasMessageContaining("总用量");
  }

  @Test void subsequentFinanceRevisionStillFollowsTheVerifiedApprovedSource() {
    var intermediate = version(15L, 10L, 2L, "DRAFT"); current.setReusedFromVersionId(15L);
    when(versions.selectById(15L)).thenReturn(intermediate);
    when(nodes.findByVersionId(15L)).thenReturn(List.of(node(12L, 7L)));
    copied.setCorrectedFromSourceNodeId(12L);
    assertThat(service.read(context)).hasSize(1);
  }

  @Test void mismatchedFrozenSupplementIsNotSilentlyAccepted() {
    raw.setQtyPerParent(new BigDecimal(".020"));
    assertThatThrownBy(() -> service.read(context)).hasMessageContaining("补录内容不一致");
  }

  @Test void currentTreeUsesItsOwnPathForTheRetainedSupplement() {
    var parent = detail("/P/ED:17/", 1, "MAKE", "E_DRAWING", "3", "3", 17L);
    var nextRaw = detail("/P/ED:17/TECH:83:RAW:7/", 2, "RAW", "TECH_RAW", ".015", ".045", 17L);
    when(details.selectList(any())).thenReturn(List.of(detail("/P/ED:7/", 1, "MAKE", "E_DRAWING", "2", "2", 7L), raw), List.of(parent, nextRaw));
    var result = service.costingInputs(2L, "2026-11");
    assertThat(result).containsOnlyKeys("/P/ED:17/");
    assertThat(result.get("/P/ED:17/").material()).isSameAs(input);
  }

  private QuoteBomSupplementVersion version(Long id, Long previous, Long result, String status) {
    var row = new QuoteBomSupplementVersion(); row.setId(id); row.setReusedFromVersionId(previous);
    row.setDrawingResultId(result); row.setVersionStatus(status); row.setQuoteProductCode("P"); row.setMaterialOrgCode("COMMERCIAL"); return row;
  }
  private ElectronicDrawingSourceNode node(Long id, Long previous) {
    var row = new ElectronicDrawingSourceNode(); row.setId(id); row.setCorrectedFromSourceNodeId(previous); row.setResolvedMaterialCode("MAKE"); return row;
  }
  private QuoteBomSupplementDetail detail(String path, int level, String material, String type, String per, String total, Long source) {
    var row = new QuoteBomSupplementDetail(); row.setPath(path); row.setLevel(level); row.setMaterialCode(material);
    row.setNodeSourceType(type); row.setQtyPerParent(new BigDecimal(per)); row.setQtyPerTop(new BigDecimal(total));
    row.setSourceElectronicNodeId(source); row.setUnit("kg"); return row;
  }
}
