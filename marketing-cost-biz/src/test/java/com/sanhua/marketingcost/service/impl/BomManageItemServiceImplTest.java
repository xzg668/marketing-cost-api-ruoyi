package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.BomManageRefreshRequest;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.entity.BomManualItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import com.sanhua.marketingcost.mapper.BomManualItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BomManageItemServiceImplTest {

  @Test
  void refreshByOaNo_insertsLeafItemsWithOaFields() {
    BomManageItemMapper manageMapper = Mockito.mock(BomManageItemMapper.class);
    BomManualItemMapper manualMapper = Mockito.mock(BomManualItemMapper.class);
    OaFormMapper oaFormMapper = Mockito.mock(OaFormMapper.class);
    OaFormItemMapper oaFormItemMapper = Mockito.mock(OaFormItemMapper.class);
    BomManageItemServiceImpl service =
        new BomManageItemServiceImpl(manageMapper, manualMapper, oaFormMapper, oaFormItemMapper);

    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("FI-SR-005-0281");
    form.setCustomer("客户A");

    OaFormItem formItem = new OaFormItem();
    formItem.setId(11L);
    formItem.setMaterialNo("P001");
    formItem.setProductName("产品A");
    formItem.setSpec("S1");
    formItem.setSunlModel("M1");

    BomManualItem root = new BomManualItem();
    root.setBomCode("BOM0001");
    root.setItemCode("P001");
    root.setBomLevel(1);

    BomManualItem mid = new BomManualItem();
    mid.setBomCode("BOM0001");
    mid.setItemCode("C001");
    mid.setParentCode("P001");

    BomManualItem leaf = new BomManualItem();
    leaf.setBomCode("BOM0001");
    leaf.setItemCode("C002");
    leaf.setParentCode("C001");

    BomManageRefreshRequest request = new BomManageRefreshRequest();
    request.setOaNo("FI-SR-005-0281");

    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(formItem));
    when(manualMapper.selectList(any())).thenReturn(List.of(root), List.of(root, mid, leaf));

    int inserted = service.refresh(request);

    assertEquals(1, inserted);
    ArgumentCaptor<BomManageItem> captor = ArgumentCaptor.forClass(BomManageItem.class);
    verify(manageMapper).insert(captor.capture());
    BomManageItem insertedItem = captor.getValue();
    assertEquals("FI-SR-005-0281", insertedItem.getOaNo());
    assertEquals("BOM0001", insertedItem.getBomCode());
    assertEquals("C002", insertedItem.getItemCode());
    assertEquals("P001", insertedItem.getMaterialNo());
    assertEquals("客户A", insertedItem.getCustomerName());
    assertEquals("产品A", insertedItem.getProductName());
  }
}
