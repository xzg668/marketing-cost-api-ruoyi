package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.ProductPropertyAnnualSyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductPropertyAnnualUsageServiceImplTest {

  @Test
  @DisplayName("OA年用量同步：事业部取OA表头，产品名称可由料品档案补齐，年用量按万换算")
  void syncFromOaFormUsesDivisionMaterialNameAndWanUnit() {
    ProductPropertyAnnualSyncService annualSyncService = mock(ProductPropertyAnnualSyncService.class);
    OaFormMapper oaFormMapper = mock(OaFormMapper.class);
    OaFormItemMapper oaFormItemMapper = mock(OaFormItemMapper.class);
    MaterialMasterRawMapper materialMasterRawMapper = mock(MaterialMasterRawMapper.class);
    when(annualSyncService.sync(any(ProductPropertyAnnualSyncRequest.class)))
        .thenReturn(new ProductPropertyAnnualSyncResult());

    MaterialMasterRaw material = new MaterialMasterRaw();
    material.setMaterialCode("P-001");
    material.setMaterialName("料品档案产品名称");
    when(materialMasterRawMapper.selectByLatestBatchAndCodes(List.of("P-001"), null))
        .thenReturn(List.of(material));

    OaForm form = new OaForm();
    form.setBusinessUnitType("COMMERCIAL");
    form.setSourceBusinessDivision("事业部（当涉及到多事业部时，请选择型号多的事业部）商用部品事业部");
    form.setOaNo("OA-001");
    form.setApplyDate(LocalDate.of(2026, 5, 20));

    OaFormItem item = new OaFormItem();
    item.setSeq(1);
    item.setMaterialNo("P-001");
    item.setAnnualVolume(new BigDecimal("1.23"));

    ProductPropertyAnnualUsageServiceImpl service =
        new ProductPropertyAnnualUsageServiceImpl(
            annualSyncService, oaFormMapper, oaFormItemMapper, materialMasterRawMapper);
    service.syncFromOaForm(form, List.of(item));

    ArgumentCaptor<ProductPropertyAnnualSyncRequest> captor =
        ArgumentCaptor.forClass(ProductPropertyAnnualSyncRequest.class);
    verify(annualSyncService).sync(captor.capture());
    ProductPropertyAnnualSyncRequest request = captor.getValue();

    assertThat(request.getRows()).hasSize(1);
    assertThat(request.getRows().get(0).getBusinessDivision()).isEqualTo("商用部品事业部");
    assertThat(request.getRows().get(0).getLevel1Name()).isEqualTo("商用部品事业部");
    assertThat(request.getRows().get(0).getProductName()).isEqualTo("料品档案产品名称");
    assertThat(request.getRows().get(0).getAnnualUsage()).isEqualByComparingTo("12300");
  }
}
