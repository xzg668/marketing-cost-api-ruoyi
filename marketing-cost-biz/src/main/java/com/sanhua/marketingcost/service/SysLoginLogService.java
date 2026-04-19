package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysLoginLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录日志查询 Service（T31）
 * <p>
 * 登录日志写入已在 {@code AuthController} 中同步落库，本服务仅暴露查询/删除能力，
 * 用于运维后台核验访问记录。
 */
public interface SysLoginLogService {

    /**
     * 分页查询登录日志
     *
     * @param pageNum   页码（从1开始）
     * @param pageSize  每页条数
     * @param userName  用户账号（模糊匹配）
     * @param ipaddr    登录IP地址（模糊匹配）
     * @param status    登录状态（0成功 1失败）
     * @param beginTime 开始时间（login_time >=）
     * @param endTime   结束时间（login_time <=）
     * @return 分页结果，按登录时间倒序
     */
    IPage<SysLoginLog> listLogs(int pageNum, int pageSize,
                                String userName, String ipaddr, String status,
                                LocalDateTime beginTime, LocalDateTime endTime);

    /**
     * 按ID查询单条登录日志
     */
    SysLoginLog getById(Long infoId);

    /**
     * 批量逻辑删除（走 @TableLogic，deleted=1）
     *
     * @param infoIds 日志ID列表
     * @return 受影响行数
     */
    int deleteByIds(List<Long> infoIds);

    /**
     * 清空全部登录日志（逻辑删除），仅运维场景使用
     *
     * @return 受影响行数
     */
    int cleanAll();
}
