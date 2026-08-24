package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunResultServiceImplTest {

  @Test
  void readsResultHeaderFromUniqueVersionAndMasterData() {
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
    MaterialMasterMapper materialMapper = mock(MaterialMasterMapper.class);
    CostRunResultServiceImpl service =
        new CostRunResultServiceImpl(versionMapper, formMapper, itemMapper, materialMapper);
    QuoteCostRunVersion version = version(7L, "SUCCESS", "189.365971");
    OaForm form = new OaForm();
    form.setOaNo("OA-001");
    form.setCustomer("客户A");
    form.setSourceBusinessDivision("商用制冷");
    form.setApplicantDept("技术部");
    OaFormItem item = new OaFormItem();
    item.setId(101L);
    item.setMaterialNo("P-001");
    item.setProductName("OA产品名");
    item.setSunlModel("MODEL-1");
    item.setProductAttr("批量品");
    MaterialMaster material = new MaterialMaster();
    material.setMaterialCode("P-001");
    material.setMaterialName("主数据产品名");
    material.setItemModel("MASTER-MODEL");
    when(versionMapper.selectList(any())).thenReturn(List.of(version));
    when(formMapper.selectOne(any())).thenReturn(form);
    when(itemMapper.selectById(101L)).thenReturn(item);
    when(materialMapper.selectOne(any())).thenReturn(material);

    var result = service.getResult("OA-001", "P-001");

    assertThat(result.getTotalCost()).isEqualByComparingTo("189.365971");
    assertThat(result.getProductName()).isEqualTo("主数据产品名");
    assertThat(result.getProductModel()).isEqualTo("MASTER-MODEL");
    assertThat(result.getCustomerName()).isEqualTo("客户A");
    assertThat(result.getCalcStatus()).isEqualTo("已核算");
  }

  @Test
  void currentSuccessWinsOverNewerStaleVersion() {
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    CostRunResultServiceImpl service =
        new CostRunResultServiceImpl(
            versionMapper,
            mock(OaFormMapper.class),
            mock(OaFormItemMapper.class),
            mock(MaterialMasterMapper.class));
    when(versionMapper.selectList(any()))
        .thenReturn(List.of(version(8L, "STALE", "200"), version(7L, "SUCCESS", "189")));

    assertThat(service.getResult("OA-001", "P-001").getTotalCost())
        .isEqualByComparingTo("189");
  }

  @Test
  void exactVersionLookupDoesNotMixAnotherVersion() {
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    CostRunResultServiceImpl service =
        new CostRunResultServiceImpl(
            versionMapper,
            mock(OaFormMapper.class),
            mock(OaFormItemMapper.class),
            mock(MaterialMasterMapper.class));
    when(versionMapper.selectById(7L)).thenReturn(version(7L, "HISTORY", "123.45"));

    assertThat(service.getResult(7L).getTotalCost()).isEqualByComparingTo("123.45");
  }

  private QuoteCostRunVersion version(Long id, String status, String totalCost) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(id);
    version.setOaNo("OA-001");
    version.setOaFormItemId(101L);
    version.setProductCode("P-001");
    version.setPricingMonth("2026-08");
    version.setResultPeriod("2026-08");
    version.setStatus(status);
    version.setTotalCost(new BigDecimal(totalCost));
    return version;
  }
}
