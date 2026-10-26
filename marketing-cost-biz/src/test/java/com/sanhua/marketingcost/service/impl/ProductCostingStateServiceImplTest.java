package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductCostingStateServiceImplTest {
  @Test void rebindingTheSamePreparationDoesNotRecursivelyChangeTheWorkspaceFingerprint() {
    var forms = mock(OaFormMapper.class);
    var items = mock(OaFormItemMapper.class);
    var prices = mock(PricePrepareItemMapper.class);
    var workspaces = mock(QuoteCostingWorkspaceService.class);
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA"); form.setBusinessUnitType("COMMERCIAL");
    var item = new OaFormItem(); item.setId(2L); item.setOaFormId(1L); item.setMaterialNo("P"); item.setBusinessUnitType("COMMERCIAL");
    var workspace = new QuoteCostingWorkspace(); workspace.setCurrentPrepareNo("PREP");
    workspace.setInputFingerprint("BOM-INITIAL"); workspace.setBomSourceFingerprint("IMMUTABLE-VARIANT");
    workspace.setBomRuleFingerprint("BOM-RULES"); workspace.setLockVersion(0);
    var price = new PricePrepareItem(); price.setSettlementKey("P/A"); price.setMaterialCode("A");
    price.setQuantity(new BigDecimal("2.5")); price.setUnitPrice(BigDecimal.TEN);
    when(forms.selectOne(any())).thenReturn(form); when(items.selectById(2L)).thenReturn(item);
    when(prices.selectList(any())).thenReturn(List.of(price));
    when(workspaces.lockOrCreate("OA", 2L, "P", "2026-10", "COMMERCIAL")).thenReturn(workspace);
    var service = new ProductCostingStateServiceImpl(forms, items, prices, workspaces,
        new QuoteCostingInputFingerprintServiceImpl(), new MonthlyPriceDocumentCodec(new ObjectMapper()));
    String first = service.bindCurrentPriceFingerprint("OA", 2L, "2026-10", "PREP");
    assertThat(service.bindCurrentPriceFingerprint("OA", 2L, "2026-10", "PREP")).isEqualTo(first);
    workspace.setBomSourceFingerprint("CHANGED-VARIANT");
    assertThat(service.bindCurrentPriceFingerprint("OA", 2L, "2026-10", "PREP")).isNotEqualTo(first);
  }
}
