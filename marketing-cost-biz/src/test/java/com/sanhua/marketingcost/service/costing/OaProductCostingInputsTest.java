package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OaProductCostingInputsTest {
  @Test
  void missingOaSalesModeCannotBecomeDirectSalesButSc020RemainsSupported() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setSourceType("WEAVER_OA");
    form.setApplyDate(LocalDate.of(2026, 9, 17));
    form.setSourceBusinessDivision("板换事业部");
    form.setApplicantDept("欧洲业务管理部");
    form.setProcessCode("FI-SC-006");
    OaFormItem item = new OaFormItem();
    item.setId(2L);
    item.setOaFormId(1L);
    item.setMaterialNo("1001");
    var forms = mock(OaFormMapper.class);
    var items = mock(OaFormItemMapper.class);
    when(forms.selectOne(any())).thenReturn(form);
    when(items.selectById(2L)).thenReturn(item);
    var resolver =
        new ProductCostingContextResolver(forms, items, mock(CostInputRevisionService.class));
    var request = new ProductCostingRequest("OA-1", 2L, null, "tester", false);
    assertThatThrownBy(() -> resolver.resolve(request)).hasMessageContaining("海外销售标识");
    form.setOverseasSalesMode("否");
    assertThat(resolver.resolve(request).form().getOverseasSalesMode()).isEqualTo("否");
    form.setOverseasSalesMode(null);
    form.setProcessCode("FI-SC-020");
    assertThat(resolver.resolve(request).productCode()).isEqualTo("1001");
    form.setSourceBusinessDivision(null);
    assertThatThrownBy(() -> resolver.resolve(request)).hasMessageContaining("事业部");
    form.setSourceType("EXCEL");
    assertThat(resolver.resolve(request).productCode()).isEqualTo("1001");
  }
}
