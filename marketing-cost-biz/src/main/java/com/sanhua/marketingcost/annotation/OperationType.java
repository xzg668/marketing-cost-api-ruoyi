package com.sanhua.marketingcost.annotation;

/**
 * 操作类型枚举
 * <p>
 * 用于 @OperationLog 注解标识业务操作类型，与 sys_operation_log.business_type 字段对应。
 */
public enum OperationType {

    /** 其它 */
    OTHER(0),
    /** 新增 */
    INSERT(1),
    /** 修改 */
    UPDATE(2),
    /** 删除 */
    DELETE(3),
    /** 导入 */
    IMPORT(5),
    /** 导出 */
    EXPORT(6);

    private final int code;

    OperationType(int code) {
        this.code = code;
    }

    /** 获取操作类型编码 */
    public int getCode() {
        return code;
    }
}
