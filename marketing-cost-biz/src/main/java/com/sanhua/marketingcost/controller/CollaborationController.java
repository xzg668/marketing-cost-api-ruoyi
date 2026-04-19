package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 协作者 Controller
 * <p>
 * 提供两类接口：
 * 1. Token 生成接口 —— 需要登录用户权限（由报价员调用，生成协作链接）
 * 2. 协作者受限 API —— 由 CollaborationSecurityFilter 认证（OA 协作者访问）
 */
@RestController
public class CollaborationController {

    private final CollaborationTokenService collaborationTokenService;

    public CollaborationController(CollaborationTokenService collaborationTokenService) {
        this.collaborationTokenService = collaborationTokenService;
    }

    // ==================== Token 生成接口（需登录） ====================

    /**
     * 生成协作 Token（24小时有效）
     *
     * @param tokenType 令牌类型（bom-supplement / price-supplement）
     * @param oaNo      报价单号（存入 remark 字段）
     * @param userId    关联用户ID
     * @return 生成的 Token 值
     */
    @PostMapping("/api/v1/collaboration/token")
    @PreAuthorize("@ss.hasPermi('cost:run:edit')")
    public CommonResult<String> generateToken(@RequestParam String tokenType,
                                              @RequestParam String oaNo,
                                              @RequestParam Long userId) {
        LpCollaborationToken record = collaborationTokenService.generateToken(userId, tokenType, oaNo, 24);
        return CommonResult.success(record.getToken());
    }

    // ==================== 协作者受限 API（Token 认证） ====================

    /**
     * BOM 补充数据入口
     * <p>
     * OA 协作者通过 Token 访问，获取报价单信息用于展示受限页面。
     * 实际的 BOM 数据补充逻辑由后续任务实现。
     */
    @GetMapping("/collaborate/bom-supplement")
    public CommonResult<Map<String, Object>> bomSupplement() {
        Map<String, Object> details = getCollaborationDetails();
        if (details == null) {
            return CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "未认证");
        }
        return CommonResult.success(Map.of(
                "tokenType", details.getOrDefault("tokenType", ""),
                "oaNo", details.getOrDefault("remark", ""),
                "message", "BOM 补充数据页面（待实现具体业务逻辑）"
        ));
    }

    /**
     * 价格补充数据入口
     * <p>
     * OA 协作者通过 Token 访问，获取报价单信息用于展示受限页面。
     * 实际的价格数据补充逻辑由后续任务实现。
     */
    @GetMapping("/collaborate/price-supplement")
    public CommonResult<Map<String, Object>> priceSupplement() {
        Map<String, Object> details = getCollaborationDetails();
        if (details == null) {
            return CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), "未认证");
        }
        return CommonResult.success(Map.of(
                "tokenType", details.getOrDefault("tokenType", ""),
                "oaNo", details.getOrDefault("remark", ""),
                "message", "价格补充数据页面（待实现具体业务逻辑）"
        ));
    }

    /**
     * 从 SecurityContext 中提取协作者 Token 详情
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getCollaborationDetails() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getDetails() == null) {
            return null;
        }
        try {
            return (Map<String, Object>) auth.getDetails();
        } catch (ClassCastException e) {
            return null;
        }
    }
}
