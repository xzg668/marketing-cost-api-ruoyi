package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.service.SysOperationLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志查询 Service 实现（T30）
 */
@Service
public class SysOperationLogServiceImpl implements SysOperationLogService {

    private final SysOperationLogMapper operationLogMapper;

    public SysOperationLogServiceImpl(SysOperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public IPage<SysOperationLog> listLogs(int pageNum, int pageSize,
                                           String operName, String title,
                                           Integer businessType, Integer status,
                                           String businessUnitType,
                                           LocalDateTime beginTime, LocalDateTime endTime) {
        LambdaQueryWrapper<SysOperationLog> wrapper = Wrappers.lambdaQuery(SysOperationLog.class);
        // 操作人 / 模块标题支持模糊匹配，其余精确匹配
        if (StringUtils.hasText(operName)) {
            wrapper.like(SysOperationLog::getOperName, operName);
        }
        if (StringUtils.hasText(title)) {
            wrapper.like(SysOperationLog::getTitle, title);
        }
        if (businessType != null) {
            wrapper.eq(SysOperationLog::getBusinessType, businessType);
        }
        if (status != null) {
            wrapper.eq(SysOperationLog::getStatus, status);
        }
        if (StringUtils.hasText(businessUnitType)) {
            wrapper.eq(SysOperationLog::getBusinessUnitType, businessUnitType);
        }
        if (beginTime != null) {
            wrapper.ge(SysOperationLog::getOperTime, beginTime);
        }
        if (endTime != null) {
            wrapper.le(SysOperationLog::getOperTime, endTime);
        }
        // 列表默认按操作时间倒序：最新的在前
        wrapper.orderByDesc(SysOperationLog::getOperTime);
        return operationLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public SysOperationLog getById(Long operId) {
        return operationLogMapper.selectById(operId);
    }

    @Override
    @Transactional
    public int deleteByIds(List<Long> operIds) {
        if (operIds == null || operIds.isEmpty()) {
            return 0;
        }
        // deleteByIds 会应用 @TableLogic，deleted=1
        return operationLogMapper.deleteByIds(operIds);
    }

    @Override
    @Transactional
    public int cleanAll() {
        // 逻辑清空：对所有未删除记录置 deleted=1；走 MP 自动 WHERE deleted=0
        return operationLogMapper.delete(Wrappers.lambdaQuery(SysOperationLog.class));
    }
}
