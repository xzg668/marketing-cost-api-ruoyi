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

    /**
     * 是否允许访问跨 BU 共享行（即 business_unit_type IS NULL 的记录）。
     * <p>
     * 为 {@code true} 时，追加的条件变为
     * {@code (column = '当前 BU' OR column IS NULL)}；
     * 默认为 {@code false}，保持严格按 BU 过滤。
     * <p>
     * 用于公共维度数据（如 {@code lp_price_variable} 的全公司共享变量），
     * 普通业务表请保持默认（false）。
     */
    boolean includeShared() default false;
}
