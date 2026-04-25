package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联动公式预览控制器 —— T14 新增。
 *
 * <p>路由：{@code POST /api/v1/price-linked/formula/preview}
 * <p>权限：{@code price:linked-item:preview}
 *
 * <p>前端编辑器按键停顿（debounce 300ms）调用一次，用于实时反馈：
 * <ol>
 *   <li>公式是否可解析（语法错 / 括号不平衡）</li>
 *   <li>每个变量实际取到什么值，来源类型是什么</li>
 *   <li>用这些变量代入后的最终数值</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedFormulaPreviewController {

  private final PriceLinkedFormulaPreviewService previewService;

  public PriceLinkedFormulaPreviewController(PriceLinkedFormulaPreviewService previewService) {
    this.previewService = previewService;
  }

  /**
   * 预览入口 —— 完全不落库；异常情况在响应体的 {@code error} 字段里返回，不抛 500。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-item:preview')")
  @PostMapping("/formula/preview")
  public CommonResult<PriceLinkedFormulaPreviewResponse> preview(
      @RequestBody PriceLinkedFormulaPreviewRequest request) {
    return CommonResult.success(previewService.preview(request));
  }
}
