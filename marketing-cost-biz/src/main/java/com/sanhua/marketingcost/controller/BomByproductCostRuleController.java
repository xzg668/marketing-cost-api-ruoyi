package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BomByproductCostRuleUpsertRequest;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.mapper.BomByproductCostRuleMapper;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 副产品附加规则 CRUD；独立维护 lp_bom_byproduct_cost_rule，避免混入树节点规则页面。 */
@RestController
@RequestMapping("/api/v1/bom/byproduct-cost-rules")
public class BomByproductCostRuleController {

  private final BomByproductCostRuleMapper ruleMapper;

  public BomByproductCostRuleController(BomByproductCostRuleMapper ruleMapper) {
    this.ruleMapper = ruleMapper;
  }

  @PreAuthorize("@ss.hasPermi('bom-data:byproduct-cost-rule:list')")
  @GetMapping
  public CommonResult<List<BomByproductCostRule>> list(
      @RequestParam(required = false) Integer enabled,
      @RequestParam(required = false) String addConditionType) {
    return CommonResult.success(
        ruleMapper.selectList(
            Wrappers.<BomByproductCostRule>lambdaQuery()
                .eq(enabled != null, BomByproductCostRule::getEnabled, enabled)
                .eq(
                    StringUtils.hasText(addConditionType),
                    BomByproductCostRule::getAddConditionType,
                    trimToNull(addConditionType))
                .orderByAsc(BomByproductCostRule::getPriority)
                .orderByAsc(BomByproductCostRule::getId)));
  }

  @PreAuthorize("@ss.hasPermi('bom-data:byproduct-cost-rule:add')")
  @PostMapping
  public CommonResult<BomByproductCostRule> create(
      @RequestBody BomByproductCostRuleUpsertRequest req) {
    BomByproductCostRule entity = new BomByproductCostRule();
    applyRequest(entity, req);
    ruleMapper.insert(entity);
    return CommonResult.success(entity);
  }

  @PreAuthorize("@ss.hasPermi('bom-data:byproduct-cost-rule:edit')")
  @PutMapping("/{id}")
  public CommonResult<BomByproductCostRule> update(
      @PathVariable Long id, @RequestBody BomByproductCostRuleUpsertRequest req) {
    BomByproductCostRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "BOM 副产品规则不存在: id=" + id);
    }
    applyRequest(existing, req);
    ruleMapper.updateById(existing);
    return CommonResult.success(existing);
  }

  @PreAuthorize("@ss.hasPermi('bom-data:byproduct-cost-rule:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(ruleMapper.deleteById(id) > 0);
  }

  @PreAuthorize("@ss.hasPermi('bom-data:byproduct-cost-rule:edit')")
  @PostMapping("/{id}/toggle")
  public CommonResult<BomByproductCostRule> toggle(@PathVariable Long id) {
    BomByproductCostRule existing = ruleMapper.selectById(id);
    if (existing == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "BOM 副产品规则不存在: id=" + id);
    }
    existing.setEnabled(Integer.valueOf(1).equals(existing.getEnabled()) ? 0 : 1);
    ruleMapper.updateById(existing);
    return CommonResult.success(existing);
  }

  private static void applyRequest(
      BomByproductCostRule entity, BomByproductCostRuleUpsertRequest req) {
    if (req.getRuleCode() != null) entity.setRuleCode(trimToNull(req.getRuleCode()));
    if (req.getRuleName() != null) entity.setRuleName(trimToNull(req.getRuleName()));
    if (req.getRuleCategory() != null) entity.setRuleCategory(trimToNull(req.getRuleCategory()));
    if (req.getAddConditionType() != null) {
      entity.setAddConditionType(trimToNull(req.getAddConditionType()));
    }
    if (req.getSettlementRowType() != null) {
      entity.setSettlementRowType(trimToNull(req.getSettlementRowType()));
    }
    if (req.getMatchConditionJson() != null) {
      entity.setMatchConditionJson(trimToNull(req.getMatchConditionJson()));
    }
    if (req.getPriority() != null) entity.setPriority(req.getPriority());
    if (req.getEnabled() != null) entity.setEnabled(req.getEnabled());
    if (req.getBusinessUnitType() != null) {
      entity.setBusinessUnitType(trimToNull(req.getBusinessUnitType()));
    }
    if (req.getBomPurpose() != null) entity.setBomPurpose(trimToNull(req.getBomPurpose()));
    if (req.getEffectiveFrom() != null) entity.setEffectiveFrom(req.getEffectiveFrom());
    if (req.getEffectiveTo() != null) entity.setEffectiveTo(req.getEffectiveTo());
    if (req.getRemark() != null) entity.setRemark(trimToNull(req.getRemark()));
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
