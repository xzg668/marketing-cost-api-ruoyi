package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.PriceVariableBindingImportResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingPendingResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import java.io.InputStream;
import java.util.List;

/**
 * 行局部变量绑定服务 —— V34 新增。
 *
 * <p>职责覆盖：
 * <ul>
 *   <li>按联动行查当前生效绑定列表 / 某 token 历史时间线</li>
 *   <li>写入（UPSERT + 版本切换）/ 软删</li>
 *   <li>"待绑定"联动行统计（公式含 B 组 token 但无绑定）</li>
 *   <li>CSV 批量导入（部分成功）</li>
 * </ul>
 *
 * <p>所有写路径都会触发
 * {@link com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl#invalidateBinding(Long)}，
 * 避免缓存不一致。
 */
public interface PriceVariableBindingService {

  /** 当前生效列表（expiry_date IS NULL 且 deleted=0）；附带 factorName 回填 */
  List<PriceVariableBindingDto> listByLinkedItem(Long linkedItemId);

  /** 某 token 的历史版本时间线（effective_date DESC） */
  List<PriceVariableBindingDto> getHistory(Long linkedItemId, String tokenName);

  /**
   * UPSERT + 版本切换：
   * <ol>
   *   <li>同 key + 同 effective_date → UPDATE 原行</li>
   *   <li>同 key 但 effective_date 更早 → 旧行 expiry_date = new.effective_date - 1d，同时 INSERT 新行</li>
   *   <li>无同 key → INSERT</li>
   * </ol>
   *
   * @return 新/更新后的 binding id
   */
  Long save(PriceVariableBindingRequest request);

  /** 软删（置 deleted=1）；列表不再返回，但 history 仍可查 */
  void softDelete(Long id);

  /** 待绑定列表 —— 公式含 B 组 token 但无当前生效 binding 的联动行 */
  PriceVariableBindingPendingResponse getPending();

  /** CSV 导入（UPSERT + 版本切换，部分成功） */
  PriceVariableBindingImportResponse importCsv(InputStream csv);
}
