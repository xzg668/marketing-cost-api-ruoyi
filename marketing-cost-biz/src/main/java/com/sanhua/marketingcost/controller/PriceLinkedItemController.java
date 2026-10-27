package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.LinkedImportedFileDto;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.service.PriceLinkedImportDispatchService;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 联动价格明细控制器 - 管理联动价格明细的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedItemController {
  private final PriceLinkedItemService priceLinkedItemService;
  private final PriceLinkedImportDispatchService priceLinkedImportDispatchService;

  @Autowired
  public PriceLinkedItemController(
      PriceLinkedItemService priceLinkedItemService,
      PriceLinkedImportDispatchService priceLinkedImportDispatchService) {
    this.priceLinkedItemService = priceLinkedItemService;
    this.priceLinkedImportDispatchService = priceLinkedImportDispatchService;
  }

  /** 查询联动价格明细列表 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items")
  public CommonResult<List<PriceLinkedItemDto>> list(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false, defaultValue = "false") Boolean includeHistory) {
    return CommonResult.success(
        priceLinkedItemService.list(
            pricingMonth, materialCode, Boolean.TRUE.equals(includeHistory)));
  }

  /** 分页查询联动价格明细列表，供料号较多的月度导入结果页使用。 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items/page")
  public CommonResult<PageResult<PriceLinkedItemDto>> page(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false, defaultValue = "false") Boolean includeHistory,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    return CommonResult.success(
        priceLinkedItemService.page(
            pricingMonth,
            materialCode,
            Boolean.TRUE.equals(includeHistory),
            page == null ? 1 : page,
            pageSize == null ? 20 : pageSize));
  }

  /** 修改联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:edit')")
  @PatchMapping("/items/{id}")
  public CommonResult<PriceLinkedItemDto> update(
      @PathVariable Long id,
      @RequestBody PriceLinkedItemUpdateRequest request) {
    PriceLinkedItemDto updated = priceLinkedItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"linked item not found");
    }
    return CommonResult.success(updated);
  }

  /** 导入联动价格明细数据（JSON 批量） */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import')")
  @PostMapping("/items/import")
  public CommonResult<List<PriceLinkedItemDto>> importItems(
      @RequestBody PriceLinkedItemImportRequest request) {
    return CommonResult.success(priceLinkedItemService.importItems(request));
  }

  /** 公共原稿确认必须经过完整预检并整批发布；技术补录仍受原审批上下文约束。 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import')")
  @PostMapping("/items/import-excel")
  public CommonResult<PriceItemImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam("pricingMonth") String pricingMonth,
      @RequestParam(value = "businessUnitType", required = false) String businessUnitType,
      @RequestParam(value = "overwriteManual", defaultValue = "false") boolean overwriteManual,
      @RequestParam(value = "effectiveStrategy", required = false) String effectiveStrategy,
      @RequestParam(value = "formulaEffectiveDate", required = false) String formulaEffectiveDate,
      @RequestParam(value = "factorPriceConflictStrategy", required = false)
          String factorPriceConflictStrategy,
      @RequestParam(value = "previewFileSha256", required = false)
          String previewFileSha256,
      @RequestParam(value = "previewFingerprint", required = false) String previewFingerprint,
      @RequestParam(value="technicalVersionId",required=false) Long technicalVersionId,
      @RequestParam(value="oaNo",required=false) String oaNo,
      @RequestParam(value="oaFormItemId",required=false) Long oaFormItemId,
      @RequestParam(value="priceOrgCode",required=false) String priceOrgCode) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    try {
      if (priceLinkedImportDispatchService != null) {
        return CommonResult.success(priceLinkedImportDispatchService.confirm(
            command(
                file,
                pricingMonth,
                businessUnitType,
                overwriteManual,
                effectiveStrategy,
                formulaEffectiveDate,
                factorPriceConflictStrategy,
                previewFileSha256).withPreviewFingerprint(previewFingerprint).withTechnicalContext(technicalContext(technicalVersionId,oaNo,oaFormItemId)).withPriceOrgCode(priceOrgCode)));
      }
      throw new IllegalStateException("联动价导入服务不可用");
    } catch (IllegalArgumentException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), e.getMessage());
    } catch (IOException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "读取上传文件失败: " + e.getMessage());
    }
  }

  /** 原稿/技术补录预检：只解析和计算，不写数据库。 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import')")
  @PostMapping("/items/import-excel/preview")
  public CommonResult<PriceLinkedType2ImportPreviewResponse> previewImportExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam("pricingMonth") String pricingMonth,
      @RequestParam(value = "businessUnitType", required = false) String businessUnitType,
      @RequestParam(value = "overwriteManual", defaultValue = "false") boolean overwriteManual,
      @RequestParam(value = "effectiveStrategy", required = false) String effectiveStrategy,
      @RequestParam(value = "formulaEffectiveDate", required = false)
          String formulaEffectiveDate,
      @RequestParam(value = "factorPriceConflictStrategy", required = false)
          String factorPriceConflictStrategy,
      @RequestParam(value="technicalVersionId",required=false) Long technicalVersionId,
      @RequestParam(value="oaNo",required=false) String oaNo,
      @RequestParam(value="oaFormItemId",required=false) Long oaFormItemId,
      @RequestParam(value="priceOrgCode",required=false) String priceOrgCode) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    if (priceLinkedImportDispatchService == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(),
          "import preview service is unavailable");
    }
    try {
      return CommonResult.success(priceLinkedImportDispatchService.preview(
          command(
              file,
              pricingMonth,
              businessUnitType,
              overwriteManual,
              effectiveStrategy,
              formulaEffectiveDate,
              factorPriceConflictStrategy,
              null).withTechnicalContext(technicalContext(technicalVersionId,oaNo,oaFormItemId)).withPriceOrgCode(priceOrgCode)));
    } catch (IllegalArgumentException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    } catch (IOException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "读取上传文件失败: " + exception.getMessage());
    }
  }

  private com.sanhua.marketingcost.dto.technicaldata.TechnicalPriceImportContext technicalContext(Long version,String oaNo,Long itemId) {
    if(version==null && oaNo==null && itemId==null)return null;
    if(version==null || oaNo==null || itemId==null)throw new IllegalArgumentException("补录导入必须同时提供报价单、产品行和技术审批版本");
    return new com.sanhua.marketingcost.dto.technicaldata.TechnicalPriceImportContext(oaNo,itemId,version);
  }

  private PriceLinkedImportCommand command(
      MultipartFile file,
      String pricingMonth,
      String businessUnitType,
      boolean overwriteManual,
      String effectiveStrategy,
      String formulaEffectiveDate,
      String factorPriceConflictStrategy,
      String previewFileSha256) throws IOException {
    return new PriceLinkedImportCommand(
        file.getBytes(),
        file.getOriginalFilename(),
        pricingMonth,
        businessUnitType,
        overwriteManual,
        effectiveStrategy,
        formulaEffectiveDate,
        factorPriceConflictStrategy,
        previewFileSha256);
  }

  /** 只查询指定月份已发布的公共联动原稿文件，不加载整批规则和日志。 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import-history:list')"
      + " or @ss.hasPermi('price:linked-item:list') or @ss.hasPermi('price:linked-item:import')")
  @GetMapping("/items/imported-files")
  public CommonResult<PageResult<LinkedImportedFileDto>> importedFiles(
      @RequestParam String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return CommonResult.success(priceLinkedItemService.importedFiles(pricingMonth, businessUnitType, page, pageSize));
  }

  /** 新增联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:add')")
  @PostMapping("/items")
  public CommonResult<PriceLinkedItemDto> create(
      @RequestBody PriceLinkedItemUpdateRequest request) {
    PriceLinkedItemDto created = priceLinkedItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 删除联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:remove')")
  @DeleteMapping("/items/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceLinkedItemService.delete(id));
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }

}
