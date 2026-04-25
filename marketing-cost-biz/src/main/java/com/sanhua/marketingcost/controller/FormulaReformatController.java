package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FormulaReformatResponse;
import com.sanhua.marketingcost.service.FormulaReformatService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员公式回洗控制器 —— Plan B T7。
 *
 * <p>一次性工具，把 {@code lp_price_linked_item.formula_expr} 全部跑过 Normalizer
 * 回写为 {@code [code]} 形态。失败行仅记录在响应里，不阻塞整体。幂等——已规范化的
 * 行 normalize 结果与原值相等，不触发 update。
 *
 * <p>权限：仅 ROLE_ADMIN（通过 {@code ss.hasPermi} 下的 admin 权限位）。
 */
@RestController
@RequestMapping("/api/v1/admin/linked")
public class FormulaReformatController {

  private final FormulaReformatService formulaReformatService;

  public FormulaReformatController(FormulaReformatService formulaReformatService) {
    this.formulaReformatService = formulaReformatService;
  }

  /**
   * 触发全表回洗 —— 返回 {total, rewrote, unchanged, failed[]}。
   * 无请求体，无参数。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-item:admin-reformat')")
  @PostMapping("/formula-reformat")
  public CommonResult<FormulaReformatResponse> reformat() {
    return CommonResult.success(formulaReformatService.reformatAll());
  }
}
