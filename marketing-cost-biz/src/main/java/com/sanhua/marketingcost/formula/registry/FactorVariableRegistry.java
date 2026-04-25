package com.sanhua.marketingcost.formula.registry;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 联动价变量注册表（factor_type 分派）—— T09 新引入的抽象。
 *
 * <p>相对旧 {@link VariableRegistry}（按 {@code source_type} 路由到多个 resolver bean）
 * 的差异：本接口按 {@code lp_price_variable.factor_type} 分四类分派到内部方法，
 * 语义与设计文档三层模型（FINANCE_FACTOR / PART_CONTEXT / FORMULA_REF / CONST）一一对应。
 *
 * <p>之所以新建而非替换旧 Registry：
 * <ul>
 *   <li>DoD 要求现有 {@code PriceLinkedCalcServiceImpl} 还能编译 —— 下一任务 T11 才切换调用方</li>
 *   <li>保留旧 Registry 给 FIXED 桶等非联动场景继续复用</li>
 * </ul>
 *
 * <p>返回 {@link Optional#empty()} 语义：
 * <ul>
 *   <li>变量未配置或 status ≠ active</li>
 *   <li>factor_type 未识别</li>
 *   <li>PART_CONTEXT 派生链断链（主材反查无结果、废料表无 ref 等）</li>
 * </ul>
 * 以上场景不是异常，业务侧应当把缺失变量按 0 或跳过处理；真正的错误（环引用、
 * JSON 解析失败等）才抛受检/非受检异常。
 */
public interface FactorVariableRegistry {

  /** 解析单个变量 —— 外部调用入口；内部自动初始化空的 visiting 栈与 request cache。 */
  Optional<BigDecimal> resolve(String variableCode, VariableContext ctx);
}
