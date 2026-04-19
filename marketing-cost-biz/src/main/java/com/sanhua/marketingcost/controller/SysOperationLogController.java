package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.service.SysOperationLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志查询 Controller（T30）
 * <p>
 * 写入由 {@code OperationLogAspect} 通过 @OperationLog 注解自动触发，此处仅暴露查询/删除能力。
 */
@RestController
@RequestMapping("/api/v1/system/operation-log")
public class SysOperationLogController {

    private final SysOperationLogService sysOperationLogService;

    public SysOperationLogController(SysOperationLogService sysOperationLogService) {
        this.sysOperationLogService = sysOperationLogService;
    }

    /**
     * 分页查询操作日志
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:operation-log:list')")
    public CommonResult<IPage<SysOperationLog>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String operName,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Integer businessType,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String businessUnitType,
            // 前端传 ISO 格式，例如 2026-04-18T00:00:00
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return CommonResult.success(sysOperationLogService.listLogs(
                pageNum, pageSize, operName, title, businessType, status,
                businessUnitType, beginTime, endTime));
    }

    /**
     * 查询单条日志详情（包含 before_data / after_data / stack_trace 等大字段）
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:operation-log:query')")
    public CommonResult<SysOperationLog> get(@PathVariable("id") Long id) {
        SysOperationLog logRecord = sysOperationLogService.getById(id);
        if (logRecord == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "操作日志不存在");
        }
        return CommonResult.success(logRecord);
    }

    /**
     * 批量删除（逻辑删除）
     */
    @DeleteMapping
    @PreAuthorize("@ss.hasPermi('system:operation-log:remove')")
    public CommonResult<Integer> delete(@RequestParam("ids") List<Long> ids) {
        return CommonResult.success(sysOperationLogService.deleteByIds(ids));
    }

    /**
     * 清空全部日志（逻辑删除），仅管理员可用
     */
    @DeleteMapping("/clean")
    @PreAuthorize("@ss.hasPermi('system:operation-log:remove')")
    public CommonResult<Integer> clean() {
        return CommonResult.success(sysOperationLogService.cleanAll());
    }
}
