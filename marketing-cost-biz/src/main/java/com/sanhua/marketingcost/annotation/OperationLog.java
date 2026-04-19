package com.sanhua.marketingcost.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * <p>
 * 标注在 Controller 方法上，AOP 切面自动记录操作日志到 sys_operation_log 表。
 * <p>
 * 使用示例：
 * <pre>
 * {@code @OperationLog(module = "费率管理", operationType = OperationType.UPDATE,
 *               recordDiff = true, targetIdParam = "id")}
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /** 模块名称（如"费率管理"、"成本试算"） */
    String module() default "";

    /** 操作类型 */
    OperationType operationType() default OperationType.OTHER;

    /**
     * 是否记录修改前后数据差异
     * <p>
     * 开启后，AOP 前置步骤会查询原值存入 before_data，方法执行后把入参存入 after_data。
     */
    boolean recordDiff() default false;

    /**
     * 目标ID参数名称
     * <p>
     * 指定方法参数中代表操作目标的参数名（如 "id"），用于记录 target_id 字段。
     */
    String targetIdParam() default "";
}
