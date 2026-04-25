package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sanhua.marketingcost.dto.BomManageRefreshRequest;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BomManageItemServiceImpl 单测。
 *
 * <p>T5.5：老的"手工 BOM → 扁平表"refresh 写入路径已下线（被 T3 导入 + T4 层级构建 + T5
 * 拍平三段流程取代），原回归测试 {@code refreshByOaNo_insertsLeafItemsWithOaFields} 删除。
 * 现在 {@link BomManageItemServiceImpl#refresh} 是 no-op，仅打 WARN 并返 0。
 */
class BomManageItemServiceImplTest {

  @Test
  @DisplayName("T5.5：refresh 下线后 no-op —— 不调 mapper、返 0")
  void refresh_deprecated_isNoop() {
    // mapper 不应该被触达 —— 任何 insert/delete/selectOne 都算 regression
    BomManageItemMapper manageMapper = mock(BomManageItemMapper.class);
    BomManageItemServiceImpl service = new BomManageItemServiceImpl(manageMapper);

    BomManageRefreshRequest request = new BomManageRefreshRequest();
    request.setOaNo("FI-SR-005-0281");

    int inserted = service.refresh(request);

    assertEquals(0, inserted, "no-op refresh 应返 0 —— 老写入逻辑已废弃");
    verifyNoInteractions(manageMapper);
  }

  @Test
  @DisplayName("T5.5：null request 也只 no-op，不炸")
  void refresh_null_isNoop() {
    BomManageItemMapper manageMapper = mock(BomManageItemMapper.class);
    BomManageItemServiceImpl service = new BomManageItemServiceImpl(manageMapper);

    assertEquals(0, service.refresh(null));
    verifyNoInteractions(manageMapper);
  }
}
