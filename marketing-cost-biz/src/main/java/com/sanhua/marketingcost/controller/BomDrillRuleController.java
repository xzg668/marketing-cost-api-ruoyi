package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.DrillRuleUpsertRequest;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import com.sanhua.marketingcost.mapper.BomStopDrillRuleMapper;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOM 过滤规则（bom_stop_drill_rule）CRUD。
 *
 * <p>软删走 entity 上的 {@code @TableLogic}，DELETE 接口实际是置 deleted=1。
 *
 * <p>路由（见父设计文档 §E.6）：
 * <ul>
 *   <li>GET /drill-rules 列表</li>
 *   <li>POST /drill-rules 新增</li>
 *   <li>PUT /drill-rules/{id} 修改</li>
 *   <li>DELETE /drill-rules/{id} 软删</li>
 *   <li>POST /drill-rules/{id}/toggle 切换启用/停用</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bom/drill-rules")
public class BomDrillRuleController {

  private final BomStopDrillRuleMapper ruleMapper;

  public BomDrillRuleController(BomStopDrillRuleMapper ruleMapper) {
    this.ruleMapper = ruleMapper;
  }

  /** 列出所有未删除规则，可按 enabled / matchType 过滤 */
  @PreAuthorize("@ss.hasPermi('base:bom:rule:list')")
  @GetMapping
  public CommonResult<List<BomStopDrillRule>> list(
      @RequestParam(required = false) Integer enabled,
      @RequestParam(required = false) String matchType) {
    return CommonResult.success(
        ruleMapper.selectList(
            Wrappers.<BomStopDrillRule>lambdaQuery()
                .eq(enabled != null, BomStopDrillRule::getEnabled, enabled)
                .eq(matchType != null, BomStopDrillRule::getMatchType, matchType)
                .orderByAsc(BomStopDrillRule::getPriority)));
  }

  /** 新增一条规则 */
  @PreAuthorize("@ss.hasPermi('base:bom:rule:add')")
  @PostMapping
  public CommonResult<BomStopDrillRule> create(@RequestBody DrillRuleUpsertRequest req) {
    BomStopDrillRule entity = new BomStopDrillRule();
    applyRequest(entity, req);
    ruleMapper.insert(entity);
    return CommonResult.success(entity);
  }

  /** 修改现有规则；id 不存在返回 BAD_REQUEST */
  @PreAuthorize("@ss.hasPermi('base:bom:rule:edit')")
  @PutMapping("/{id}")
  public CommonResult<BomStopDrillRule> update(
      @PathVariable Long id, @RequestBody DrillRuleUpsertRequest req) {
    BomStopDrillRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "规则不存在: id=" + id);
    }
    applyRequest(existing, req);
    ruleMapper.updateById(existing);
    return CommonResult.success(existing);
  }

  /** 软删：置 deleted=1（@TableLogic 自动处理） */
  @PreAuthorize("@ss.hasPermi('base:bom:rule:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    int affected = ruleMapper.deleteById(id);
    return CommonResult.success(affected > 0);
  }

  /** 切换启用/停用（enabled 0↔1） */
  @PreAuthorize("@ss.hasPermi('base:bom:rule:edit')")
  @PostMapping("/{id}/toggle")
  public CommonResult<BomStopDrillRule> toggle(@PathVariable Long id) {
    BomStopDrillRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "规则不存在: id=" + id);
    }
    existing.setEnabled(existing.getEnabled() != null && existing.getEnabled() == 1 ? 0 : 1);
    ruleMapper.updateById(existing);
    return CommonResult.success(existing);
  }

  // ============================ 私有 ============================

  private static void applyRequest(BomStopDrillRule entity, DrillRuleUpsertRequest req) {
    if (req.getMatchType() != null) entity.setMatchType(req.getMatchType());
    if (req.getMatchValue() != null) entity.setMatchValue(req.getMatchValue());
    // T8：复合条件 JSON 字符串透传（允许清空 → 判 != null 才覆盖，空串也视为显式设置）
    if (req.getMatchConditionJson() != null) {
      entity.setMatchConditionJson(
          req.getMatchConditionJson().isEmpty() ? null : req.getMatchConditionJson());
    }
    if (req.getDrillAction() != null) entity.setDrillAction(req.getDrillAction());
    if (req.getMarkSubtreeCostRequired() != null)
      entity.setMarkSubtreeCostRequired(req.getMarkSubtreeCostRequired());
    if (req.getReplaceToCode() != null) entity.setReplaceToCode(req.getReplaceToCode());
    if (req.getPriority() != null) entity.setPriority(req.getPriority());
    if (req.getEnabled() != null) entity.setEnabled(req.getEnabled());
    if (req.getEffectiveFrom() != null) entity.setEffectiveFrom(req.getEffectiveFrom());
    if (req.getEffectiveTo() != null) entity.setEffectiveTo(req.getEffectiveTo());
    if (req.getBusinessUnitType() != null) entity.setBusinessUnitType(req.getBusinessUnitType());
    if (req.getRemark() != null) entity.setRemark(req.getRemark());
  }
}
