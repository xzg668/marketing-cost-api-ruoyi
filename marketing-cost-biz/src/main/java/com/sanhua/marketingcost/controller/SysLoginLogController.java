package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysLoginLog;
import com.sanhua.marketingcost.service.SysLoginLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录日志查询 Controller（T31）
 * <p>
 * 写入由 {@code AuthController} 的 login/logout 同步落库，此处仅暴露查询/删除能力。
 */
@RestController
@RequestMapping("/api/v1/system/login-log")
public class SysLoginLogController {

    private final SysLoginLogService sysLoginLogService;

    public SysLoginLogController(SysLoginLogService sysLoginLogService) {
        this.sysLoginLogService = sysLoginLogService;
    }

    /**
     * 分页查询登录日志
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:login-log:list')")
    public CommonResult<IPage<SysLoginLog>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String ipaddr,
            @RequestParam(required = false) String status,
            // 前端传 ISO 格式，例如 2026-04-18T00:00:00
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return CommonResult.success(sysLoginLogService.listLogs(
                pageNum, pageSize, userName, ipaddr, status, beginTime, endTime));
    }

    /**
     * 查询单条日志详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:login-log:query')")
    public CommonResult<SysLoginLog> get(@PathVariable("id") Long id) {
        SysLoginLog logRecord = sysLoginLogService.getById(id);
        if (logRecord == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "登录日志不存在");
        }
        return CommonResult.success(logRecord);
    }

    /**
     * 批量删除（逻辑删除）
     */
    @DeleteMapping
    @PreAuthorize("@ss.hasPermi('system:login-log:remove')")
    public CommonResult<Integer> delete(@RequestParam("ids") List<Long> ids) {
        return CommonResult.success(sysLoginLogService.deleteByIds(ids));
    }

    /**
     * 清空全部登录日志（逻辑删除），仅管理员可用
     */
    @DeleteMapping("/clean")
    @PreAuthorize("@ss.hasPermi('system:login-log:remove')")
    public CommonResult<Integer> clean() {
        return CommonResult.success(sysLoginLogService.cleanAll());
    }
}
