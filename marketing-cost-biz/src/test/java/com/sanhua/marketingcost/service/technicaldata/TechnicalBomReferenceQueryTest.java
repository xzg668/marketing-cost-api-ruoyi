package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalBomReferenceQueryTest {
  private final FormalBomReadService formal = mock(FormalBomReadService.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final BomRawHierarchyMapper hierarchy = mock(BomRawHierarchyMapper.class);
  private final BomU9SourceMapper u9 = mock(BomU9SourceMapper.class);
  private final TechnicalBomReferenceQuery service = new TechnicalBomReferenceQuery(formal, materials, hierarchy, u9,
      new OaMessageCodec(new ObjectMapper().findAndRegisterModules()));
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L, 2L, "OA-NEW", "2026-11", "COMMERCIAL", "NEW",
      "当前新品", "", "NEW-MODEL", "210", "COMMERCIAL", LocalDate.of(2026,11,1), LocalDateTime.of(2026,11,16,12,0));
  private QuoteBomSourceLineDto line(long id, int level, String parent, String code, String path, String quantity, Long u9Id) {
    var raw = new BomRawHierarchy(); raw.setId(id); raw.setPriceOrgCode("210"); raw.setPath(path); raw.setSourceImportBatchId("BATCH"); raw.setMaterialCode(code); raw.setBomVersion("F001");
    when(hierarchy.selectById(id)).thenReturn(raw);
    var master = new MaterialMasterRaw(); master.setMaterialCode(code); master.setMaterialName(code + "名称"); master.setUnit("kg");
    master.setMaterialModel(code + "-MODEL"); master.setOrganizationCode("COMMERCIAL");
    when(materials.selectByLatestBatchAndCodes(List.of(code), null, "COMMERCIAL")).thenReturn(List.of(master));
    return new QuoteBomSourceLineDto(id, (int) id, level, "TOP", parent, code, code + "名称", "", code + "-MODEL", null,
        level == 1 ? "制造件" : "采购件", "301", "原材料", "kg", "采购件", "主要材料", "主制造", "F001",
        new BigDecimal(quantity), null, null, path, (int) id, id, u9Id, 0, "210", "COMMERCIAL");
  }
  private void source(List<QuoteBomSourceLineDto> lines) {
    when(formal.readReference(eq("TOP"), eq("2026-11"), eq("主制造"), eq(context.bomEffectiveDate()), eq(context.organization())))
        .thenReturn(new FormalBomReadResult("TOP", "2026-11", "主制造", true, lines, null));
  }
  @Test void completeBranchIdentityAndBaseEvidenceArePreservedWithoutOverwritingTarget() {
    var root = line(1, 0, "TOP", "TOP", "/TOP/", "1", null);
    var parent = line(2, 1, "TOP", "MAKE", "/TOP/MAKE@1/", "3", null);
    var raw = line(3, 2, "MAKE", "RAW", "/TOP/MAKE@1/RAW@1/", "2", 10L);
    var duplicateParent = line(4, 1, "TOP", "MAKE", "/TOP/MAKE@2/", "4", null);
    var duplicateRaw = line(5, 2, "MAKE", "RAW", "/TOP/MAKE@2/RAW@1/", "0.5", null);
    source(List.of(root, parent, raw, duplicateParent, duplicateRaw));
    var ods = new BomU9Source(); ods.setId(10L); ods.setPriceOrgCode("210"); ods.setParentMaterialNo("MAKE");
    ods.setChildMaterialNo("RAW"); ods.setBomVersion("F001"); ods.setImportBatchId("BATCH");
    ods.setQtyPerParent(new BigDecimal("2")); ods.setParentBaseQty(new BigDecimal("100")); when(u9.selectById(10L)).thenReturn(ods);
    var result = service.read(context, "TOP");
    assertThat(result.rows()).extracting(TechnicalBomRows.Row::hierarchy).containsExactly("1", "1.1", "2", "2.1");
    assertThat(result.rows().get(1).quantity()).isEqualByComparingTo("0.02");
    var quantities = TechnicalBomRows.cumulativeQuantities(result.rows());
    assertThat(quantities.get("1.1")).isEqualByComparingTo("0.06");
    assertThat(quantities.get("2.1")).isEqualByComparingTo("2");
    assertThat(result.sourceLines().get("1.1").sourceChildQuantity()).isEqualByComparingTo("2");
    assertThat(result.sourceLines().get("1.1").parentBaseQuantity()).isEqualByComparingTo("100");
    assertThat(context.productCode()).isEqualTo("NEW");
    assertThat(ods.getQtyPerParent()).isEqualByComparingTo("2");
    verify(u9, never()).updateById(any(BomU9Source.class));
  }
  @Test void ambiguousMaterialAndMissingParentAreNotSilentlySelected() {
    var root = line(1, 0, "TOP", "TOP", "/TOP/", "1", null);
    var child = line(2, 2, "MISSING", "RAW", "/TOP/MISSING/RAW/", "1", null);
    source(List.of(root, child));
    assertThatThrownBy(() -> service.read(context, "TOP")).hasMessageContaining("父子关系");
    var master = new MaterialMasterRaw(); master.setMaterialCode("TOP");
    when(materials.selectByLatestBatchAndCodes(List.of("TOP"), null, "COMMERCIAL")).thenReturn(List.of(master, master));
    assertThatThrownBy(() -> service.read(context, "TOP")).hasMessageContaining("唯一有效档案");
  }
  @Test void baseIsAppliedOnlyToOriginalQuantityAndMustBePositive() {
    assertThat(TechnicalBomReferenceQuery.relative(new BigDecimal("2"), new BigDecimal("100"))).isEqualByComparingTo("0.02");
    assertThat(TechnicalBomReferenceQuery.relative(new BigDecimal("0.02"), null)).isEqualByComparingTo("0.02");
    assertThatThrownBy(() -> TechnicalBomReferenceQuery.relative(BigDecimal.ONE, BigDecimal.ZERO)).hasMessageContaining("底数");
  }
  @Test void formalCrossOrganizationBranchKeepsItsOwnMaterialAndPriceOrganization() {
    var root = line(1, 0, "TOP", "TOP", "/TOP/", "1", null);
    var child = line(2, 1, "TOP", "PLATE-PART", "/TOP/PLATE-PART/", "2", null);
    var mapper = new ObjectMapper();
    var document = mapper.valueToTree(child);
    ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("priceOrgCode", "220").put("materialOrganizationCode", "PLATE");
    child = mapper.convertValue(document, QuoteBomSourceLineDto.class);
    var raw = new BomRawHierarchy(); raw.setId(2L); raw.setPriceOrgCode("220"); raw.setMaterialCode("PLATE-PART"); raw.setBomVersion("F001");
    when(hierarchy.selectById(2L)).thenReturn(raw);
    var master = new MaterialMasterRaw(); master.setMaterialCode("PLATE-PART"); master.setUnit("kg"); master.setOrganizationCode("PLATE");
    when(materials.selectByLatestBatchAndCodes(List.of("PLATE-PART"), null, "PLATE")).thenReturn(List.of(master));
    source(List.of(root, child));
    var result = service.read(context, "TOP");
    assertThat(result.sourceLines().get("1").priceOrgCode()).isEqualTo("220");
    assertThat(result.sourceLines().get("1").materialOrgCode()).isEqualTo("PLATE");
    assertThat(result.rows().getFirst().quantity()).isEqualByComparingTo("2");
    verify(materials, never()).selectByLatestBatchAndCodes(List.of("PLATE-PART"), null, "COMMERCIAL");
  }
}
