package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.AuxSubjectImportRequest;
import com.sanhua.marketingcost.dto.AuxSubjectPageResponse;
import com.sanhua.marketingcost.dto.AuxSubjectQuoteResponse;
import com.sanhua.marketingcost.dto.AuxSubjectRequest;
import com.sanhua.marketingcost.entity.AuxSubject;
import com.sanhua.marketingcost.service.AuxSubjectService;
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
import org.springframework.util.StringUtils;

/**
 * 辅助科目控制器 - 管理辅助科目的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/aux-subjects")
public class AuxSubjectController {
  private final AuxSubjectService auxSubjectService;

  public AuxSubjectController(AuxSubjectService auxSubjectService) {
    this.auxSubjectService = auxSubjectService;
  }

  /** 查询辅助科目列表 */
  @PreAuthorize("@ss.hasPermi('base:aux-subject:list')")
  @GetMapping
  public CommonResult<AuxSubjectPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String auxSubjectCode,
      @RequestParam(required = false) String period,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<AuxSubject> pager =
        auxSubjectService.page(materialCode, auxSubjectCode, period, current, size);
    return CommonResult.success(new AuxSubjectPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增辅助科目 */
  @PreAuthorize("@ss.hasPermi('base:aux-subject:add')")
  @PostMapping
  public CommonResult<AuxSubject> create(@RequestBody AuxSubjectRequest request) {
    AuxSubject created = auxSubjectService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改辅助科目 */
  @PreAuthorize("@ss.hasPermi('base:aux-subject:edit')")
  @PatchMapping("/{id}")
  public CommonResult<AuxSubject> update(
      @PathVariable Long id,
      @RequestBody AuxSubjectRequest request) {
    AuxSubject updated = auxSubjectService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"aux subject not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除辅助科目 */
  @PreAuthorize("@ss.hasPermi('base:aux-subject:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(auxSubjectService.delete(id));
  }

  /** 导入辅助科目数据 */
  @PreAuthorize("@ss.hasPermi('base:aux-subject:import')")
  @PostMapping("/import")
  public CommonResult<List<AuxSubject>> importItems(
      @RequestBody AuxSubjectImportRequest request) {
    return CommonResult.success(auxSubjectService.importItems(request));
  }

  /** 查询辅助科目报价 */
  @PreAuthorize("@ss.hasPermi('base:aux-subject:list')")
  @GetMapping("/quote")
  public CommonResult<AuxSubjectQuoteResponse> quote(
      @RequestParam String refMaterialCode,
      @RequestParam String auxSubjectCode,
      @RequestParam String period) {
    if (!StringUtils.hasText(refMaterialCode)
        || !StringUtils.hasText(auxSubjectCode)
        || !StringUtils.hasText(period)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"missing params");
    }
    var unitPrice = auxSubjectService.quoteUnitPrice(refMaterialCode, auxSubjectCode, period);
    return CommonResult.success(new AuxSubjectQuoteResponse(unitPrice));
  }
}
