package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysLoginLog;
import com.sanhua.marketingcost.mapper.SysLoginLogMapper;
import com.sanhua.marketingcost.service.SysLoginLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录日志查询 Service 实现（T31）
 */
@Service
public class SysLoginLogServiceImpl implements SysLoginLogService {

    private final SysLoginLogMapper loginLogMapper;

    public SysLoginLogServiceImpl(SysLoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    @Override
    public IPage<SysLoginLog> listLogs(int pageNum, int pageSize,
                                       String userName, String ipaddr, String status,
                                       LocalDateTime beginTime, LocalDateTime endTime) {
        LambdaQueryWrapper<SysLoginLog> wrapper = Wrappers.lambdaQuery(SysLoginLog.class);
        // 用户名 / IP 支持模糊匹配；状态精确匹配（"0"成功 "1"失败）
        if (StringUtils.hasText(userName)) {
            wrapper.like(SysLoginLog::getUserName, userName);
        }
        if (StringUtils.hasText(ipaddr)) {
            wrapper.like(SysLoginLog::getIpaddr, ipaddr);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysLoginLog::getStatus, status);
        }
        if (beginTime != null) {
            wrapper.ge(SysLoginLog::getLoginTime, beginTime);
        }
        if (endTime != null) {
            wrapper.le(SysLoginLog::getLoginTime, endTime);
        }
        // 列表默认按登录时间倒序：最新的在前
        wrapper.orderByDesc(SysLoginLog::getLoginTime);
        return loginLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public SysLoginLog getById(Long infoId) {
        return loginLogMapper.selectById(infoId);
    }

    @Override
    @Transactional
    public int deleteByIds(List<Long> infoIds) {
        if (infoIds == null || infoIds.isEmpty()) {
            return 0;
        }
        // deleteByIds 会应用 @TableLogic，deleted=1
        return loginLogMapper.deleteByIds(infoIds);
    }

    @Override
    @Transactional
    public int cleanAll() {
        // 逻辑清空：对所有未删除记录置 deleted=1；走 MP 自动 WHERE deleted=0
        return loginLogMapper.delete(Wrappers.lambdaQuery(SysLoginLog.class));
    }
}
