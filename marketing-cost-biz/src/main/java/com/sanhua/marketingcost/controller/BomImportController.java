package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.dto.ImportAndBuildResult;
import com.sanhua.marketingcost.service.BomImportService;
import java.io.IOException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * BOM 三层架构 · 阶段 A 导入接口。
 *
 * <p>路由前缀 {@code /api/v1/bom}；鉴权沿用项目 {@code @ss.hasPermi(...)} 表达式，
 * 权限 code 与 BomManageItemController 的 {@code base:bom:*} 同源（管理端已有菜单配置）。
 *
 * <p>和老 {@code BomManageItemController} 的区别：
 * <ul>
 *   <li>新接口读写新表 lp_bom_u9_source（三层架构第 1 层 ODS）</li>
 *   <li>老接口保留（T5.5 才会下线），两者并存</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bom")
public class BomImportController {

  private final BomImportService bomImportService;

  public BomImportController(BomImportService bomImportService) {
    this.bomImportService = bomImportService;
  }

  /**
   * 导入 U9 Excel 到 lp_bom_u9_source。
   *
   * <p>表单字段：
   * <ul>
   *   <li>{@code file} —— .xlsx（MultipartFile，必填）</li>
   * </ul>
   * 响应含 importBatchId + 统计 + 错误明细；单行错误不会让整批失败。
   */
  @PreAuthorize("@ss.hasPermi('base:bom:import')")
  @PostMapping("/import")
  public CommonResult<BomImportResult> importExcel(
      @RequestPart("file") MultipartFile file, Authentication auth) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    String importedBy = auth == null ? null : auth.getName();
    try {
      return CommonResult.success(
          bomImportService.importExcel(
              file.getInputStream(), file.getOriginalFilename(), importedBy));
    } catch (IOException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    }
  }

  /**
   * 财务一键端点：上传 Excel 自动完成"导入 + 按 purpose 批量构建层级"两步。
   *
   * <p>和 {@link #importExcel} 的区别：后者只做阶段 A，调用方需要再调
   * {@code /build-hierarchy} 才能得到可查询的树；本端点一次性做完 A + B，
   * 面向不关心技术阶段的业务用户（财务）。
   *
   * <p>权限复用 {@code base:bom:import}（同 import），假设能上传就有权触发构建。
   */
  @PreAuthorize("@ss.hasPermi('base:bom:import')")
  @PostMapping("/import-and-build")
  public CommonResult<ImportAndBuildResult> importAndBuild(
      @RequestPart("file") MultipartFile file, Authentication auth) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    String importedBy = auth == null ? null : auth.getName();
    try {
      return CommonResult.success(
          bomImportService.importAndBuild(
              file.getInputStream(), file.getOriginalFilename(), importedBy));
    } catch (IOException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    }
  }

  /**
   * 列出某层的导入批次（按时间倒序分页）。
   *
   * <p>T3 阶段只实现 {@code layer=U9_SOURCE}；RAW_HIERARCHY / COSTING_ROW 由 T4 / T5 补。
   */
  @PreAuthorize("@ss.hasPermi('base:bom:list')")
  @GetMapping("/batches")
  public CommonResult<List<BomBatchSummary>> listBatches(
      @RequestParam String layer,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(bomImportService.listBatches(layer, page, size));
  }
}
