package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.aspect.OperationLogDiffContext;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyEnabledRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyQuery;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyResponse;
import com.sanhua.marketingcost.service.MaterialQuoteShapePolicyService;
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

/** 规则菜单使用的料品报价形态规则维护接口。 */
@RestController
@RequestMapping("/api/v1/bom/material-shape-policies")
public class MaterialQuoteShapePolicyController {

  private final MaterialQuoteShapePolicyService service;

  public MaterialQuoteShapePolicyController(
      MaterialQuoteShapePolicyService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('bom-data:material-shape-policy:list')")
  public CommonResult<List<MaterialQuoteShapePolicyResponse>> list(
      @RequestParam(required = false) String materialOrgCode,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String materialName,
      @RequestParam(required = false) String materialSpec,
      @RequestParam(required = false) String materialModel,
      @RequestParam(required = false) String policyMode,
      @RequestParam(required = false) Integer enabled,
      @RequestParam(required = false) String effectiveMonth) {
    MaterialQuoteShapePolicyQuery query =
        new MaterialQuoteShapePolicyQuery();
    query.setMaterialOrgCode(materialOrgCode);
    query.setMaterialCode(materialCode);
    query.setMaterialName(materialName);
    query.setMaterialSpec(materialSpec);
    query.setMaterialModel(materialModel);
    query.setPolicyMode(policyMode);
    query.setEnabled(enabled);
    query.setEffectiveMonth(effectiveMonth);
    return CommonResult.success(service.list(query));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('bom-data:material-shape-policy:list')")
  public CommonResult<MaterialQuoteShapePolicyResponse> get(
      @PathVariable Long id) {
    return CommonResult.success(service.get(id));
  }

  @PostMapping
  @PreAuthorize("@ss.hasPermi('bom-data:material-shape-policy:edit')")
  @OperationLog(
      module = "料品形态规则",
      operationType = OperationType.INSERT,
      recordDiff = true)
  public CommonResult<MaterialQuoteShapePolicyResponse> create(
      @RequestBody MaterialQuoteShapePolicyRequest request) {
    MaterialQuoteShapePolicyResponse created = service.create(request);
    OperationLogDiffContext.record(null, created);
    return CommonResult.success(created);
  }

  @PutMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('bom-data:material-shape-policy:edit')")
  @OperationLog(
      module = "料品形态规则",
      operationType = OperationType.UPDATE,
      recordDiff = true,
      targetIdParam = "id")
  public CommonResult<MaterialQuoteShapePolicyResponse> update(
      @PathVariable Long id,
      @RequestBody MaterialQuoteShapePolicyRequest request) {
    MaterialQuoteShapePolicyResponse before = service.get(id);
    MaterialQuoteShapePolicyResponse updated = service.update(id, request);
    OperationLogDiffContext.record(before, updated);
    return CommonResult.success(updated);
  }

  @PutMapping("/{id}/enabled")
  @PreAuthorize("@ss.hasPermi('bom-data:material-shape-policy:toggle')")
  @OperationLog(
      module = "料品形态规则",
      operationType = OperationType.UPDATE,
      recordDiff = true,
      targetIdParam = "id")
  public CommonResult<MaterialQuoteShapePolicyResponse> setEnabled(
      @PathVariable Long id,
      @RequestBody MaterialQuoteShapePolicyEnabledRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("启停内容不能为空");
    }
    MaterialQuoteShapePolicyResponse before = service.get(id);
    MaterialQuoteShapePolicyResponse updated =
        service.setEnabled(id, request.getEnabled());
    OperationLogDiffContext.record(before, updated);
    return CommonResult.success(updated);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('bom-data:material-shape-policy:edit')")
  @OperationLog(
      module = "料品形态规则",
      operationType = OperationType.DELETE,
      recordDiff = true,
      targetIdParam = "id")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    MaterialQuoteShapePolicyResponse before = service.get(id);
    boolean deleted = service.delete(id);
    OperationLogDiffContext.record(before, null);
    return CommonResult.success(deleted);
  }
}
