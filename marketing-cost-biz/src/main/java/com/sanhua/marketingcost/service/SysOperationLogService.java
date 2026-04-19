package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.entity.system.SysOperationLog;

import java.time.LocalDateTime;

/**
 * 操作日志查询 Service（T30）
 * <p>
 * 仅提供查询/删除能力，写入由 {@code OperationLogAspect} 异步完成。
 */
public interface SysOperationLogService {

    /**
     * 分页查询操作日志
     *
     * @param pageNum          页码
     * @param pageSize         每页条数
     * @param operName         操作人（模糊）
     * @param title            模块标题（模糊）
     * @param businessType     业务类型（0其它 1新增 2修改 3删除）
     * @param status           状态（0正常 1异常）
     * @param businessUnitType 业务单元类型（COMMERCIAL/HOUSEHOLD）
     * @param beginTime        开始时间（oper_time >=）
     * @param endTime          结束时间（oper_time <=）
     * @return 分页结果
     */
    IPage<SysOperationLog> listLogs(int pageNum, int pageSize,
                                    String operName, String title,
                                    Integer businessType, Integer status,
                                    String businessUnitType,
                                    LocalDateTime beginTime, LocalDateTime endTime);

    /**
     * 按 ID 查询单条日志（含 before_data / after_data / stack_trace 大字段）
     */
    SysOperationLog getById(Long operId);

    /**
     * 批量逻辑删除（走 @TableLogic，deleted=1）
     *
     * @param operIds 操作日志 ID 列表
     * @return 受影响行数
     */
    int deleteByIds(java.util.List<Long> operIds);

    /**
     * 清空（逻辑删除）所有操作日志，一般只在维护场景使用
     *
     * @return 受影响行数
     */
    int cleanAll();
}
