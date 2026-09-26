package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElectronicDrawingProductLookupTest {
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingProductLookup lookup = new ElectronicDrawingProductLookup(items, materials);
  private final ElectronicDrawingWorkContext context = new ElectronicDrawingWorkContext(
      11L, 0, 1L, null, 10L, 11L, "TEST-11", "OA-10", "P1", null, null, null, null,
      "NON_BARE", "FULL_BOM", "2026-09", "210", "COMMERCIAL", "COMMERCIAL", "210",
      true, true, "NEED_TECH", null, null, null, null);

  private OaFormItem item() {
    var item = new OaFormItem(); item.setOaFormId(10L);
    when(items.selectById(11L)).thenReturn(item);
    return item;
  }

  @Test void oaDrawingCanBeUsedWithoutFabricatingOtherMissingProductFields() {
    var item = item(); item.setCustomerDrawing("OA-DRAW");
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("OA-DRAW");
    verifyNoInteractions(materials);
    assertThat(item.getMaterialNo()).isNull(); assertThat(item.getSunlModel()).isNull();
  }

  @Test void modelOnlyRequiresRealDrawingChoiceWithinTheMaterialOrganization() {
    var item = item(); item.setSunlModel("MODEL");
    var unrelated = material("P3", "UNRELATED", "WRONG"); unrelated.setMaterialSpec("MODEL");
    when(materials.selectByDrawingIdentities(anySet(), isNull(), eq("COMMERCIAL"), anyInt()))
        .thenReturn(List.of(material("P1", "MODEL", "DRAW-A"), material("P2", "MODEL", "DRAW-B"), unrelated));
    assertThat(lookup.options(context)).extracting(ElectronicDrawingProductLookup.Option::drawingNo).containsExactly("DRAW-A", "DRAW-B");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "MODEL")).hasMessageContaining("实际查到");
    assertThatThrownBy(() -> lookup.requireDrawing(context, null)).hasMessageContaining("实际查到");
    assertThatThrownBy(() -> lookup.requireDrawing(context, "WRONG")).hasMessageContaining("实际查到");
    assertThat(lookup.requireDrawing(context, "DRAW-B")).isEqualTo("DRAW-B");
    assertThat(item.getMaterialNo()).isNull(); assertThat(item.getCustomerDrawing()).isNull();
  }

  @Test void materialCodeLooksUpItsDrawingWithoutGuessingFromNameOrModel() {
    var item = item(); item.setMaterialNo("P1"); item.setSunlModel("MODEL");
    when(materials.selectByLatestBatchAndCodes(anySet(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(material("P1", "MODEL", "REAL-DRAW")));
    assertThat(lookup.requireDrawing(context, null)).isEqualTo("REAL-DRAW");
    verify(materials, never()).selectByDrawingIdentities(anySet(), any(), any(), anyInt());
  }

  private MaterialMasterRaw material(String code, String model, String drawing) {
    var material = new MaterialMasterRaw(); material.setMaterialCode(code); material.setMaterialModel(model);
    material.setDrawingNo(drawing); return material;
  }
}
