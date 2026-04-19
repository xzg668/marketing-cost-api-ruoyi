package com.sanhua.marketingcost.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限注解 — 标注在 Mapper 查询方法上，开启业务单元数据隔离。
 * <p>
 * 工作方式：当被注解方法执行查询时，{@code BusinessUnitInterceptor} 会自动向 SQL 的
 * WHERE 子句追加业务单元过滤条件，例如：
 * <pre>
 *   AND business_unit_type = '当前用户的业务单元'
 * </pre>
 * <p>
 * 规则：
 * <ul>
 *   <li>ADMIN 角色 / 拥有 {@code *:*:*} 权限 → 不追加条件（可看全部）</li>
 *   <li>BU_DIRECTOR / BU_STAFF → 追加本人 business_unit_type 过滤</li>
 *   <li>未登录或 SecurityContext 中无 businessUnitType → 不追加（保持原行为）</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {

    /**
     * 业务单元字段所在表别名。为空表示不使用别名（仅单表查询场景）。
     * 例如联表时设为 {@code "u"} 会生成 {@code u.business_unit_type = ?}。
     */
    String alias() default "";

    /**
     * 业务单元过滤字段名，默认 {@code business_unit_type}。
     */
    String column() default "business_unit_type";
}
