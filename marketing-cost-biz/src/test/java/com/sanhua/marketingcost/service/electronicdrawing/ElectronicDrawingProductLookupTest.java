package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationProperties;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElectronicDrawingProductLookupTest {
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final DrawingVerificationProperties properties = new DrawingVerificationProperties();
  private final ElectronicDrawingProductLookup lookup =
      new ElectronicDrawingProductLookup(items, materials, properties);
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
  void masterLookupIsEnabledByDefaultEvenWhenTheQuoteHasADrawing() {
    assertThat(properties.isUseMaterialMaster()).isTrue();
    item().setCustomerDrawing("PROVIDER-TEST-MX");
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("料品档案");
    verifyNoInteractions(materials);
  }

  @Test
  void directModeUsesQuoteDrawingWithoutReadingTheMaterialMaster() {
    properties.setUseMaterialMaster(false);
    var item = item();
    item.setCustomerDrawing("PROVIDER-TEST-MX");
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("PROVIDER-TEST-MX");
    assertThat(item.getMaterialNo()).isNull();
    item.setCustomerDrawing("PROVIDER-TEST");
    assertThat(lookup.requireDrawing(context, "PROVIDER-TEST-MX")).isEqualTo("PROVIDER-TEST-MX");
    assertThat(lookup.drawingOptions(context)).singleElement()
        .satisfies(option -> assertThat(option.source()).isEqualTo("QUOTE_CUSTOMER_DRAWING"));
    verifyNoInteractions(materials);
  }

  @Test
  void directModeRequiresAnExplicitQuoteDrawingAndRejectsADifferentRequestedDrawing() {
    properties.setUseMaterialMaster(false);
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("SPEC");
    item.setMaterialNo("101850563");
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("填写客户图号");
    item.setCustomerDrawing("PROVIDER-TEST-MX");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "OTHER-MX"))
        .hasMessageContaining("实际查到");
    item.setOaFormId(999L);
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("不存在");
    verifyNoInteractions(materials);
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
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("REAL-DRAW-MX");
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
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("SPECIFIC-DRAW-MX");
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
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("FALLBACK-DRAW-MX");
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
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("唯一图号");
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
  void materialDrawingAddsMxOnlyWhenMissing() {
    item().setMaterialNo("P1");
    when(materials.selectDrawingProductCandidates("P1", null, null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", null, "YCQB05L89")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("YCQB05L89-MX");
    assertThat(lookup.requireDrawing(context, "YCQB05L89-MX")).isEqualTo("YCQB05L89-MX");
    when(materials.selectDrawingProductCandidates("P1", null, null, "COMMERCIAL"))
        .thenReturn(List.of(material("P1", null, "YCQB05L89-MX")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("YCQB05L89-MX");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "YCQB05L89-MAX"))
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
    assertThat(lookup.requireDrawing(context, "DRAW-B")).isEqualTo("DRAW-B-MX");
    assertThat(item.getMaterialNo()).isNull();
    assertThat(item.getCustomerDrawing()).isNull();
  }

  @Test
  void sameDrawingOnMultipleMaterialsCanBeUsedWithoutChoosingAMaterial() {
    var item = item();
    item.setSunlModel("MODEL");
    item.setSpec("SPEC");
    when(materials.selectDrawingProductCandidates(null, "MODEL", "SPEC", "COMMERCIAL"))
        .thenReturn(List.of(material("P1", "MODEL", "SHARED"), material("P2", "MODEL", "SHARED")));
    assertThat(lookup.options(context)).hasSize(2);
    assertThat(lookup.drawingOptions(context))
        .singleElement()
        .satisfies(option -> {
          assertThat(option.drawingNo()).isEqualTo("SHARED-MX");
          assertThat(option.materialNo()).isNull();
          assertThat(option.name()).isNull();
        });
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("SHARED-MX");
    assertThat(lookup.requireDrawing(context, "SHARED")).isEqualTo("SHARED-MX");
    assertThat(item.getMaterialNo()).isNull();
    verify(materials, never()).selectDrawingProductCandidates(null, "MODEL", null, "COMMERCIAL");
    item.setMaterialNo("P2");
    when(materials.selectDrawingProductCandidates("P2", null, null, "COMMERCIAL"))
        .thenReturn(List.of(material("P2", "MODEL", "SHARED")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("SHARED-MX");
  }

  @Test
  void sameDrawingIsComparedWithoutCaseOrSurroundingWhitespace() {
    item().setSunlModel("YCQB05L89");
    when(materials.selectDrawingProductCandidates(null, "YCQB05L89", null, "COMMERCIAL"))
        .thenReturn(List.of(
            material("101850563", "YCQB05L89", " YCQB05L89 "),
            material("1145900000152", "YCQB05L89", "ycqb05l89")));
    assertThat(lookup.drawingOptions(context)).hasSize(1);
    assertThat(lookup.options(context)).hasSize(2);
    assertThat(lookup.requireDrawing(context, "ycqb05l89")).isEqualTo("YCQB05L89-MX");
  }

  @Test
  void repeatedDrawingCanBeChosenAmongDifferentDrawings() {
    item().setSunlModel("YCQB05L89");
    when(materials.selectDrawingProductCandidates(null, "YCQB05L89", null, "COMMERCIAL"))
        .thenReturn(List.of(
            material("101850563", "YCQB05L89", "YCQB05L89"),
            material("1145900000152", "YCQB05L89", "YCQB05L89"),
            material("3000000000001", "YCQB05L89", "OTHER-DRAW")));
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("实际查到");
    assertThat(lookup.requireDrawing(context, "YCQB05L89")).isEqualTo("YCQB05L89-MX");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "YCQB05L89-MAX"))
        .hasMessageContaining("实际查到");
  }

  @Test
  void baseAndMxMasterDrawingsBecomeTheSameRequestChoice() {
    item().setSunlModel("YCQB05L89");
    when(materials.selectDrawingProductCandidates(null, "YCQB05L89", null, "COMMERCIAL"))
        .thenReturn(List.of(
            material("101850563", "YCQB05L89", "YCQB05L89"),
            material("1145900000152", "YCQB05L89", "YCQB05L89-MX")));
    assertThat(lookup.drawingOptions(context))
        .singleElement()
        .satisfies(option -> {
          assertThat(option.drawingNo()).isEqualTo("YCQB05L89-MX");
          assertThat(option.materialNo()).isNull();
        });
    assertThat(lookup.options(context)).hasSize(2);
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("YCQB05L89-MX");
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
