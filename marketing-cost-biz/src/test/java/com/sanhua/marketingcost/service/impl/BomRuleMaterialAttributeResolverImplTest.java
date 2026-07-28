package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BomRuleMaterialAttributeResolverImplTest {

  @Test
  @DisplayName("辅料规则属性按组织从 lp_material_master_raw 返回主分类编码和采购分类")
  void resolvesRawMaterialMasterAttributesByOrganization() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    MaterialMasterRaw master = new MaterialMasterRaw();
    master.setMaterialCode("  MAT-1 ");
    master.setMainCategoryCode(" 171721412 ");
    master.setPurchaseCategory(" PEEK ");
    when(mapper.selectByLatestBatchAndCodes(any(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(master));

    Map<String, BomRuleMaterialAttributes> result =
        new BomRuleMaterialAttributeResolverImpl(mapper)
            .resolve(Set.of(" MAT-1 ", "MISSING"), "COMMERCIAL");

    assertThat(result).containsOnlyKeys("MAT-1");
    assertThat(result.get("MAT-1"))
        .isEqualTo(new BomRuleMaterialAttributes("171721412", "PEEK"));
  }
}
