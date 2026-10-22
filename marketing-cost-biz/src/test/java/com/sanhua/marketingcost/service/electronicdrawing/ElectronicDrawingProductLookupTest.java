package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElectronicDrawingProductLookupTest {
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingProductLookup lookup =
      new ElectronicDrawingProductLookup(items, materials);
  private final ElectronicDrawingWorkContext context =
      new ElectronicDrawingWorkContext(
          11L,
          0,
          1L,
          null,
          10L,
          11L,
          "TEST-11",
          "OA-10",
          "P1",
          null,
          null,
          null,
          null,
          "NON_BARE",
          "FULL_BOM",
          "2026-09",
          "210",
          "COMMERCIAL",
          "COMMERCIAL",
          "210",
          true,
          true,
          "NEED_TECH",
          null,
          null,
          null,
          null);

  private OaFormItem item() {
    var item = new OaFormItem();
    item.setOaFormId(10L);
    item.setBusinessUnitType("COMMERCIAL");
    when(items.selectById(11L)).thenReturn(item);
    return item;
  }

  @Test
  void suppliedDrawingAloneDoesNotReplaceMaterialOrModelLookup() {
    var item = item();
    item.setCustomerDrawing("OA-DRAW");
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("未查到");
    verifyNoInteractions(materials);
  }

  @Test
  void materialCodeAloneHasPriorityOverModelSpecificationAndSuppliedDrawing() {
    var item = item();
    item.setMaterialNo("P1");
    item.setSunlModel("WRONG-MODEL");
    item.setSpec("WRONG-SPEC");
    item.setCustomerDrawing("WRONG-DRAW");
    when(materials.selectDrawingProductCandidates("P1", null, null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "REAL-DRAW")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("REAL-DRAW");
    verify(materials).selectDrawingProductCandidates("P1", null, null, "COMMERCIAL");
    verifyNoMoreInteractions(materials);
  }

  @Test
  void suppliedButMissingMaterialCodeDoesNotFallBackToAnotherMaterialWithTheModel() {
    var item = item();
    item.setMaterialNo("MISSING");
    item.setSunlModel("MODEL");
    item.setSpec("SPEC");
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("未查到");
    verify(materials).selectDrawingProductCandidates("MISSING", null, null, "COMMERCIAL");
    verifyNoMoreInteractions(materials);
  }

  @Test
  void noMaterialCodeUsesModelAndSpecificationBeforeAnyBroaderLookup() {
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("SPEC-A");
    item.setCustomerDrawing("OLD-DRAW");
    when(materials.selectDrawingProductCandidates(null, "MODEL", "SPEC-A", "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "SPECIFIC-DRAW")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("SPECIFIC-DRAW");
    verify(materials).selectDrawingProductCandidates(null, "MODEL", "SPEC-A", "COMMERCIAL");
    verifyNoMoreInteractions(materials);
  }

  @Test
  void noExactModelSpecificationMatchFallsBackToModelOnlyInOrder() {
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("UNKNOWN-SPEC");
    when(materials.selectDrawingProductCandidates(null, "MODEL", "UNKNOWN-SPEC", "COMMERCIAL"))
        .thenReturn(List.of());
    when(materials.selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "FALLBACK-DRAW")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("FALLBACK-DRAW");
    var ordered = inOrder(materials);
    ordered
        .verify(materials)
        .selectDrawingProductCandidates(null, "MODEL", "UNKNOWN-SPEC", "COMMERCIAL");
    ordered.verify(materials).selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL");
    ordered.verifyNoMoreInteractions();
  }

  @Test
  void exactSpecificationAmbiguityDoesNotBroadenOrChooseTheFirstRow() {
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("SPEC");
    when(materials.selectDrawingProductCandidates(null, "MODEL", "SPEC", "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "DRAW-A"), material("P2", "MODEL", "DRAW-B")));
    assertThat(lookup.options(context)).hasSize(2);
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("唯一料品");
    verify(materials, never()).selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL");
  }

  @Test
  void exactMatchedMasterWithoutDrawingDoesNotSelectAnotherSpecification() {
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("SPEC");
    when(materials.selectDrawingProductCandidates(null, "MODEL", "SPEC", "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", null)));
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("未查到");
    verify(materials, never()).selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL");
  }

  @Test
  void materialAloneUsesItsActualDrawingWithoutAddingSuffix() {
    item().setMaterialNo("P1");
    when(materials.selectDrawingProductCandidates("P1", null, null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", null, "YCQB05L89")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("YCQB05L89");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "YCQB05L89-MX"))
        .hasMessageContaining("实际查到");
  }

  @Test
  void modelOnlyWorksWithNoSpecificationAndNeverUsesModelAsDrawing() {
    var item = item();
    item.setSunlModel("MODEL");
    when(materials.selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "DRAW-A"), material("P2", "MODEL", "DRAW-B")));
    assertThat(lookup.options(context))
        .extracting(ElectronicDrawingProductLookup.Option::drawingNo)
        .containsExactly("DRAW-A", "DRAW-B");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "MODEL")).hasMessageContaining("实际查到");
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("实际查到");
    assertThat(lookup.requireDrawing(context, "DRAW-B")).isEqualTo("DRAW-B");
    assertThat(item.getMaterialNo()).isNull();
    assertThat(item.getCustomerDrawing()).isNull();
  }

  @Test
  void sameDrawingOnMultipleMaterialsIsNotAUniqueProduct() {
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("SPEC");
    when(materials.selectDrawingProductCandidates(null, "MODEL", "SPEC", "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "SHARED"), material("P2", "MODEL", "SHARED")));
    assertThat(lookup.options(context)).hasSize(2);
    assertThatThrownBy(() -> lookup.requireDrawing(context, "SHARED")).hasMessageContaining("唯一料品");
    item.setMaterialNo("P2");
    when(materials.selectDrawingProductCandidates("P2", null, null, "COMMERCIAL"))
        .thenReturn(List.of(material("P2", "MODEL", "SHARED")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("SHARED");
  }

  @Test
  void blankSourceNeverScansTheMasterOrInventsDrawingFromName() {
    item().setProductName("DRAW-LOOKING-NAME");
    assertThat(lookup.options(context)).isEmpty();
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("未查到");
    verifyNoInteractions(materials);
  }

  @Test
  void missingDrawingOnMatchedMasterDoesNotBecomeAModelFallback() {
    item().setSunlModel("MODEL");
    when(materials.selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", null)));
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("未查到");
  }

  @Test
  void wrongQuoteAndBusinessUnitAreRejectedBeforeMasterAccess() {
    var item = item();
    item.setOaFormId(999L);
    assertThatThrownBy(() -> lookup.options(context)).hasMessageContaining("不存在");
    item.setOaFormId(10L);
    item.setBusinessUnitType("OTHER");
    assertThatThrownBy(() -> lookup.options(context)).hasMessageContaining("业务单元");
    verifyNoInteractions(materials);
  }

  private MaterialMasterRaw material(String code, String model, String drawing) {
    var material = new MaterialMasterRaw();
    material.setMaterialCode(code);
    material.setMaterialModel(model);
    material.setDrawingNo(drawing);
    return material;
  }
}
