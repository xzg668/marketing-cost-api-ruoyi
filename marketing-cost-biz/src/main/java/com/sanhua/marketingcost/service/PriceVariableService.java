package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceVariableRequest;
import com.sanhua.marketingcost.dto.VariableCatalogResponse;
import com.sanhua.marketingcost.entity.PriceVariable;
import java.util.List;

/**
 * 价格变量服务 —— 覆盖查询、分类目录和运维 CRUD。
 */
public interface PriceVariableService {

  /** 平铺列表：可按 {@code status} 过滤，给后台管理页使用。 */
  List<PriceVariable> list(String status);

  /**
   * 分类目录 —— T15 新增。
   *
   * <p>按 {@code factor_type} 分三组返回，仅包含 {@code status='active'} 的变量；
   * {@code CONST} 不暴露给前端编辑器。对 {@code FINANCE_FACTOR}，
   * 会批量回查 {@code lp_finance_base_price} 带出最新一期基准价信息。
   */
  VariableCatalogResponse catalog();

  /**
   * 单条详情 —— 管理页编辑态初始化用。
   *
   * @throws IllegalArgumentException 当 id 不存在
   */
  PriceVariable getById(Long id);

  /**
   * 新增一条价格变量。
   *
   * <p>校验：variableCode 全局唯一；resolverKind + resolverParams schema 对齐；
   * 写入成功后 {@link com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl#invalidate()}
   * 被调用，运行中的 registry 缓存立刻失效。
   *
   * @return 新行 id
   */
  Long create(PriceVariableRequest request);

  /**
   * 更新已有变量 —— variableCode 不允许改（作为外部引用标识）。
   *
   * @throws IllegalArgumentException 当 id 不存在 / 试图改 variableCode
   */
  void update(Long id, PriceVariableRequest request);

  /**
   * 软删：把 status 置 inactive —— 不物理删，保留历史 formula 引用。
   *
   * <p>物理删可能破坏历史 formula 的 {@code [code]} 反向渲染 & trace 重演，禁止。
   */
  void softDelete(Long id);
}
