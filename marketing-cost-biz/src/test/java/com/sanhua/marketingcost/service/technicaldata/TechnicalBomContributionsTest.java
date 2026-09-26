package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput.MaterialLine;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalBomContributionsTest {
  private final EffectiveTechnicalDataQueryService inputs = mock(EffectiveTechnicalDataQueryService.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final TechnicalBomContributions service = new TechnicalBomContributions(inputs,materials);

  @Test void keepsOrdinaryTreeWhenThereAreNoApprovedMaterialContributions() {
    var tree = List.of(row("PRODUCT",0,"/PRODUCT/"));
    assertThat(service.merge(10L,"2026-09",tree)).isSameAs(tree);
    verifyNoInteractions(materials);
  }

  @Test void addsPerProductPackageQuantityOnlyOnceAndPreservesRepeatedMaterialOccurrences() {
    use(List.of(line("PACKAGE","box-1","BOX","6","pcs"),line("PACKAGE","box-2","BOX","0.0000000000000001","pcs")));
    when(materials.selectByLatestBatchAndCodes(anyList(),isNull(),eq("COMMERCIAL")))
        .thenReturn(List.of(master("BOX","pcs","PACK")));
    var tree = List.of(row("PRODUCT",0,"/PRODUCT/"));
    var result = service.merge(10L,"2026-09",tree);
    assertThat(result).hasSize(3);
    assertThat(result.get(1).getQtyPerTop()).isEqualByComparingTo("6");
    assertThat(result.get(2).getQtyPerTop()).isEqualByComparingTo("0.0000000000000001");
    assertThat(result.subList(1,3)).extracting(BomRawHierarchy::getSourceType).containsOnly("TECH_PACKAGE");
    assertThat(result.subList(1,3)).extracting(BomRawHierarchy::getSourceLineKey).doesNotHaveDuplicates();
    assertThat(service.merge(10L,"2026-09",tree)).extracting(BomRawHierarchy::getPath)
        .containsExactlyElementsOf(result.stream().map(BomRawHierarchy::getPath).toList());
    assertThat(tree).hasSize(1);
  }

  @Test void replacesDrawingSolderAndConvertsKgToPurchaseGramsWithoutRoundingSmallQuantities() {
    use(List.of(line("SOLDER","weld-1","WELD","0.00000000001","kg")));
    when(materials.selectByLatestBatchAndCodes(anyList(),isNull(),eq("COMMERCIAL")))
        .thenReturn(List.of(master("WELD","g","181810001"), master("CLEANER","kg","181811435")));
    var result = service.merge(10L,"2026-09", List.of(row("PRODUCT",0,"/PRODUCT/"),
        row("WELD",1,"/PRODUCT/WELD/"),row("CHILD",2,"/PRODUCT/WELD/CHILD/"),
        row("CLEANER",1,"/PRODUCT/CLEANER/")));
    assertThat(result).extracting(BomRawHierarchy::getMaterialCode).containsExactly("PRODUCT","CLEANER","WELD");
    assertThat(result.getLast().getQtyPerTop()).isEqualByComparingTo("0.00000001");
    assertThat(result.getLast().getSourceType()).isEqualTo("TECH_SOLDER");
  }

  @Test void rejectsChangedPurchaseUnitsInsteadOfApplyingTheWrongPrice() {
    use(List.of(line("PACKAGE","box","BOX","1","pcs")));
    when(materials.selectByLatestBatchAndCodes(anyList(),isNull(),eq("COMMERCIAL")))
        .thenReturn(List.of(master("BOX","kg","PACK")));
    assertThatThrownBy(() -> service.merge(10L,"2026-09",List.of(row("PRODUCT",0,"/PRODUCT/"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("单位");
  }

  @Test void rejectsSolderWhoseCurrentMaterialClassificationChanged() {
    use(List.of(line("SOLDER","weld","WELD","1","kg")));
    when(materials.selectByLatestBatchAndCodes(anyList(),isNull(),eq("COMMERCIAL")))
        .thenReturn(List.of(master("WELD","kg","181811435")));
    assertThatThrownBy(() -> service.merge(10L,"2026-09",List.of(row("PRODUCT",0,"/PRODUCT/"))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分类已变化");
  }

  private void use(List<MaterialLine> lines) {
    when(inputs.preparationMaterials(10L,"2026-09")).thenReturn(lines);

  }
  private MaterialLine line(String module,String key,String code,String qty,String unit) {
    return new MaterialLine(module,key,code,code,new BigDecimal(qty),unit,30L);
  }
  private BomRawHierarchy row(String code,int level,String path) {
    var row = new BomRawHierarchy(); row.setMaterialCode(code);row.setTopProductCode("PRODUCT");
    row.setLevel(level);row.setPath(path);row.setPriceOrgCode("210");row.setBusinessUnitType("COMMERCIAL");
    row.setQtyPerParent(BigDecimal.ONE);row.setQtyPerTop(BigDecimal.ONE);return row;
  }
  private MaterialMasterRaw master(String code,String unit,String category) {
    var row = new MaterialMasterRaw();row.setMaterialCode(code);row.setUnit(unit);row.setMainCategoryCode(category);return row;
  }
}
