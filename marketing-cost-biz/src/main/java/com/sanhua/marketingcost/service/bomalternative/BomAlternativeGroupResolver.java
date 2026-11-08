package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.List;

/** 从正式 BOM 原始层级中只读识别标准/替代组。 */
public interface BomAlternativeGroupResolver {

  /**
   * 识别结构正确的替代组，并同时返回所有结构异常。
   *
   * <p>U9读取副本缺少类型时按同版本来源补齐；不保存报价选择、不裁剪 BOM，也不写业务数据。
   */
  BomAlternativeGroupResolution resolve(List<BomRawHierarchy> rows);
}
