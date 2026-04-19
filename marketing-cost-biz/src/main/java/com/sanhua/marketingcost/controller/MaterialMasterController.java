package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MaterialMasterImportRequest;
import com.sanhua.marketingcost.dto.MaterialMasterPageResponse;
import com.sanhua.marketingcost.dto.MaterialMasterRequest;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.service.MaterialMasterService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 物料主数据控制器 - 管理物料基础数据的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/materials")
public class MaterialMasterController {
  private final MaterialMasterService materialMasterService;

  public MaterialMasterController(MaterialMasterService materialMasterService) {
    this.materialMasterService = materialMasterService;
  }

  /** 查询物料主数据列表 */
  @PreAuthorize("@ss.hasPermi('base:material:list')")
  @GetMapping
  public CommonResult<MaterialMasterPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String materialName,
      @RequestParam(required = false) String spec,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String drawingNo,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false) String material,
      @RequestParam(required = false) String bizUnit,
      @RequestParam(required = false) String productionDept,
      @RequestParam(required = false) String productionWorkshop,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<MaterialMaster> pager = materialMasterService.page(
        materialCode, materialName, spec, model, drawingNo, shapeAttr, material,
        bizUnit, productionDept, productionWorkshop, current, size);
    return CommonResult.success(new MaterialMasterPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增物料主数据 */
  @PreAuthorize("@ss.hasPermi('base:material:add')")
  @PostMapping
  public CommonResult<MaterialMaster> create(@RequestBody MaterialMasterRequest request) {
    MaterialMaster created = materialMasterService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改物料主数据 */
  @PreAuthorize("@ss.hasPermi('base:material:edit')")
  @PatchMapping("/{id}")
  public CommonResult<MaterialMaster> update(
      @PathVariable Long id,
      @RequestBody MaterialMasterRequest request) {
    MaterialMaster updated = materialMasterService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"material not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除物料主数据 */
  @PreAuthorize("@ss.hasPermi('base:material:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(materialMasterService.delete(id));
  }

  /** 导入物料主数据 */
  @PreAuthorize("@ss.hasPermi('base:material:import')")
  @PostMapping("/import")
  public CommonResult<List<MaterialMaster>> importItems(
      @RequestBody MaterialMasterImportRequest request) {
    return CommonResult.success(materialMasterService.importItems(request));
  }
}
