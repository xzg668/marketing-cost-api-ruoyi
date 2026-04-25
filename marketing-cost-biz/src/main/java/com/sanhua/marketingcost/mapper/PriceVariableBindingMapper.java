package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 行局部变量绑定 Mapper。
 *
 * <p>注意：BaseMapper 继承后 {@code @TableLogic} 生效，常规 selectById/selectList
 * 自动过滤 {@code deleted=0}；历史查询需要绕过软删过滤，故用 {@code @Select} 自定义 SQL。
 */
@Mapper
public interface PriceVariableBindingMapper extends BaseMapper<PriceVariableBinding> {

  /**
   * 查一条联动行"当前生效"的所有 token 绑定。
   *
   * <p>当前生效 = {@code expiry_date IS NULL AND deleted=0}。
   * evaluator 缓存按 linked_item_id 缓存这个列表。
   */
  @Select("SELECT id, linked_item_id, token_name, factor_code, price_source, bu_scoped,"
      + " effective_date, expiry_date, source, confirmed_by, confirmed_at, remark,"
      + " created_by, created_at, updated_by, updated_at, deleted"
      + " FROM lp_price_variable_binding"
      + " WHERE linked_item_id = #{linkedItemId}"
      + "   AND expiry_date IS NULL"
      + "   AND deleted = 0")
  List<PriceVariableBinding> findCurrentByLinkedItemId(@Param("linkedItemId") Long linkedItemId);

  /**
   * 查一条联动行某个 token 的历史版本（含已过期、已软删），按生效日倒序。
   *
   * <p>用于"变量绑定历史"抽屉展示修改轨迹，不走 @TableLogic 过滤。
   */
  @Select("SELECT id, linked_item_id, token_name, factor_code, price_source, bu_scoped,"
      + " effective_date, expiry_date, source, confirmed_by, confirmed_at, remark,"
      + " created_by, created_at, updated_by, updated_at, deleted"
      + " FROM lp_price_variable_binding"
      + " WHERE linked_item_id = #{linkedItemId}"
      + "   AND token_name = #{tokenName}"
      + " ORDER BY effective_date DESC, id DESC")
  List<PriceVariableBinding> findHistory(
      @Param("linkedItemId") Long linkedItemId,
      @Param("tokenName") String tokenName);

  /**
   * 查当前生效的同 (linked_item_id, token_name) 记录（用于写入前检查是否需要版本切换）。
   *
   * <p>命中 0 条 → 首次绑定；命中 1 条且 effective_date 相同 → 原地更新；
   * 命中 1 条但 effective_date 不同 → 版本切换（旧行 expiry_date = new - 1d）。
   */
  @Select("SELECT id, linked_item_id, token_name, factor_code, price_source, bu_scoped,"
      + " effective_date, expiry_date, source, confirmed_by, confirmed_at, remark,"
      + " created_by, created_at, updated_by, updated_at, deleted"
      + " FROM lp_price_variable_binding"
      + " WHERE linked_item_id = #{linkedItemId}"
      + "   AND token_name = #{tokenName}"
      + "   AND expiry_date IS NULL"
      + "   AND deleted = 0"
      + " LIMIT 1")
  PriceVariableBinding findCurrentByLinkedItemIdAndToken(
      @Param("linkedItemId") Long linkedItemId,
      @Param("tokenName") String tokenName);

  /**
   * 把旧版本的 {@code expiry_date} 设为指定日期（版本切换时调）。
   *
   * @param id         旧行 id
   * @param expiryDate new.effective_date - 1 day
   */
  @Update("UPDATE lp_price_variable_binding SET expiry_date = #{expiryDate},"
      + " updated_at = CURRENT_TIMESTAMP"
      + " WHERE id = #{id}")
  int expireById(@Param("id") Long id, @Param("expiryDate") LocalDate expiryDate);

  /**
   * 列出公式中可能含行局部占位符但无当前绑定的联动行。
   *
   * <p>用于"待绑定"徽章：前端 /price/linked/result 顶部提示。
   * SQL 语义：
   * <pre>
   *   select li.id, li.material_code, li.spec_model, li.formula_expr
   *   from lp_price_linked_item li
   *   where li.deleted = 0                         -- V35 起：软删过滤（@TableLogic 自动改写只作用于 BaseMapper；自定义 SQL 需手工带）
   *     and li.effective_to IS NULL                -- 只看当前生效版本
   *     and li.formula_expr LIKE '%[!_!_%' ESCAPE '!'  -- 公式里含 [__xxx] 前缀的任何占位符
   *     and NOT EXISTS (
   *       select 1 from lp_price_variable_binding b
   *        where b.linked_item_id = li.id and b.deleted=0 and b.expiry_date is null
   *     )
   * </pre>
   *
   * <p>V36：从硬编码的 {@code [__material]} / {@code [__scrap]} 升级为通配
   * {@code [__%}。依赖约定"行局部占位符统一用两下划线前缀"（见
   * {@link com.sanhua.marketingcost.entity.RowLocalPlaceholder} 注释），
   * 只要有新 {@code __packaging} / {@code __coating} 之类录入 DB，这条 SQL 即自动覆盖。
   *
   * <p>{@code ESCAPE '!'} 子句：MySQL 里 {@code _} 是 LIKE 通配符（匹配任一字符），
   * 必须转义才能表达字面下划线。用 {@code !} 当转义符（规避 {@code \} 在 Java 源码里
   * 的双层转义噪音）。
   *
   * <p>已删除 {@code formula_expr_cn} 相关的中文 LIKE —— 该列自 T6 起是
   * {@link com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer} 派生的
   * 展示字段，不再是数据源头；扫源头 {@code formula_expr} 即可。
   */
  @Select("SELECT li.id, li.material_code, li.spec_model, li.formula_expr, li.formula_expr_cn"
      + " FROM lp_price_linked_item li"
      + " WHERE li.deleted = 0"
      + "   AND li.effective_to IS NULL"
      + "   AND li.formula_expr LIKE '%[!_!_%' ESCAPE '!'"
      + "   AND NOT EXISTS ("
      + "     SELECT 1 FROM lp_price_variable_binding b"
      + "      WHERE b.linked_item_id = li.id"
      + "        AND b.deleted = 0"
      + "        AND b.expiry_date IS NULL)"
      + " ORDER BY li.id")
  List<PendingItemRow> findPendingItems();

  /** 结果视图：pending 列表只需要几列，不完整 entity。 */
  class PendingItemRow {
    private Long id;
    private String materialCode;
    private String specModel;
    private String formulaExpr;
    private String formulaExprCn;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getSpecModel() {
      return specModel;
    }

    public void setSpecModel(String specModel) {
      this.specModel = specModel;
    }

    public String getFormulaExpr() {
      return formulaExpr;
    }

    public void setFormulaExpr(String formulaExpr) {
      this.formulaExpr = formulaExpr;
    }

    public String getFormulaExprCn() {
      return formulaExprCn;
    }

    public void setFormulaExprCn(String formulaExprCn) {
      this.formulaExprCn = formulaExprCn;
    }
  }
}
