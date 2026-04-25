package com.sanhua.marketingcost.security;

import com.sanhua.marketingcost.annotation.DataScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusinessUnitInterceptor 单元测试。
 * <p>
 * 重点覆盖三类行为：
 * <ol>
 *   <li>SQL 改写是否正确（有 WHERE / 无 WHERE / 带别名 / UNION）</li>
 *   <li>{@link BusinessUnitContext} 上下文读取（ADMIN 跳过、BU_STAFF 追加）</li>
 *   <li>{@link DataScope} 注解解析（被标注的 Mapper 方法 → 解析到；未标注 → null）</li>
 * </ol>
 * 完整 MyBatis Plus 拦截链的集成测试在 E2E 阶段覆盖。
 */
class BusinessUnitInterceptorTest {

    private final BusinessUnitInterceptor interceptor = new BusinessUnitInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ========== SQL 改写 ==========

    /** 无 WHERE 的简单查询 → 追加 WHERE */
    @Test
    void appendWhereToSqlWithoutWhere() throws Exception {
        String sql = "SELECT * FROM lp_quotation";
        String out = interceptor.appendBusinessUnitCondition(sql, "", "business_unit_type", "COMMERCIAL");
        assertTrue(out.toUpperCase().contains("WHERE"));
        assertTrue(out.contains("business_unit_type = 'COMMERCIAL'"));
    }

    /** 已有 WHERE → 使用 AND 追加 */
    @Test
    void appendAndToSqlWithExistingWhere() throws Exception {
        String sql = "SELECT id, name FROM lp_quotation WHERE status = 1";
        String out = interceptor.appendBusinessUnitCondition(sql, "", "business_unit_type", "HOUSEHOLD");
        assertTrue(out.contains("status = 1"));
        assertTrue(out.contains("AND business_unit_type = 'HOUSEHOLD'"));
    }

    /** 带表别名 → 列名前加别名 */
    @Test
    void appendWithTableAlias() throws Exception {
        String sql = "SELECT q.id FROM lp_quotation q WHERE q.status = 1";
        String out = interceptor.appendBusinessUnitCondition(sql, "q", "business_unit_type", "COMMERCIAL");
        assertTrue(out.contains("q.business_unit_type = 'COMMERCIAL'"));
    }

    /** UNION 查询 → 每个子 SELECT 都追加条件 */
    @Test
    void appendToUnionSelects() throws Exception {
        String sql = "SELECT id FROM lp_quotation UNION SELECT id FROM lp_quotation_archived";
        String out = interceptor.appendBusinessUnitCondition(sql, "", "business_unit_type", "COMMERCIAL");
        int count = out.split("business_unit_type = 'COMMERCIAL'", -1).length - 1;
        assertEquals(2, count, "UNION 的两个子查询都应追加条件");
    }

    /** includeShared=true → 生成 (bu = 'X' OR bu IS NULL) 形态，允许看跨 BU 共享行 */
    @Test
    void appendIncludeSharedBranchAddsOrIsNull() throws Exception {
        String sql = "SELECT id FROM lp_price_variable";
        String out = interceptor.appendBusinessUnitCondition(
                sql, "", "business_unit_type", "COMMERCIAL", true);
        assertTrue(out.contains("business_unit_type = 'COMMERCIAL'"),
                "必须包含当前 BU 等值条件");
        assertTrue(out.contains("business_unit_type IS NULL"),
                "includeShared=true 时必须补上 IS NULL 分支");
        assertTrue(out.contains(" OR "),
                "两条件应以 OR 拼接");
    }

    /**
     * v1.4 回归：派生子查询场景（SELECT COUNT(1) FROM (SELECT 1 FROM tbl ...) t）
     * 不能把 WHERE 加到外层（派生表 t 里没有 business_unit_type 列，数据库会抛
     * "Unknown column"）；必须穿透到子查询里加到真表上。
     */
    @Test
    void appendDerivedSubquery_pushesFilterInside() throws Exception {
        String sql = "SELECT COUNT(1) FROM ("
                + "SELECT 1 FROM lp_bom_manual_item WHERE deleted = 0 GROUP BY bom_code"
                + ") t";
        String out = interceptor.appendBusinessUnitCondition(
                sql, "", "business_unit_type", "COMMERCIAL");
        // 条件落在子查询里（deleted = 0 附近），不是外层 t 的 WHERE
        assertTrue(out.contains("business_unit_type = 'COMMERCIAL'"),
                "应该追加 BU 条件：" + out);
        // 关键：外层不应有 WHERE（因为派生表里没这列，加到外层会报错）
        int idxFromT = out.lastIndexOf(") t");
        assertTrue(idxFromT > 0, "派生表结构应保留：" + out);
        String tail = out.substring(idxFromT);
        assertFalse(tail.toUpperCase().contains("WHERE"),
                "条件必须加到子查询，不能加到外层 t 的 WHERE：" + out);
        // 子查询的 WHERE 应包含原 deleted=0 + 新 BU 条件
        assertTrue(out.contains("deleted = 0"), "保留原 WHERE：" + out);
    }

    /**
     * v1.4 回归：派生子查询 + 外层无 WHERE 场景。
     * 和上面类似但子查询内部没有 WHERE（只有 GROUP BY）。
     */
    @Test
    void appendDerivedSubquery_subqueryHadNoWhere() throws Exception {
        String sql = "SELECT COUNT(1) FROM (SELECT 1 FROM lp_price_variable GROUP BY variable_code) t";
        String out = interceptor.appendBusinessUnitCondition(
                sql, "", "business_unit_type", "COMMERCIAL");
        assertTrue(out.contains("business_unit_type = 'COMMERCIAL'"));
        // 确保条件是加在子查询 FROM 后，不是外层
        int derivedEnd = out.lastIndexOf(") t");
        String tail = out.substring(derivedEnd);
        assertFalse(tail.toUpperCase().contains("WHERE"),
                "条件不能加到外层派生表 t 上：" + out);
    }

    /** includeShared=true + 已有 WHERE → 追加到现有条件后（用括号包 OR 避免歧义） */
    @Test
    void appendIncludeSharedWithExistingWhere() throws Exception {
        String sql = "SELECT id FROM lp_price_variable WHERE status = 'active'";
        String out = interceptor.appendBusinessUnitCondition(
                sql, "", "business_unit_type", "COMMERCIAL", true);
        assertTrue(out.contains("status = 'active'"));
        // 断言 OR 被括号包裹，防止和外面的 AND 混算
        assertTrue(out.contains("(business_unit_type = 'COMMERCIAL' OR business_unit_type IS NULL)"),
                "OR 必须被括号包裹，避免与外层 AND 的优先级混淆：" + out);
    }

    // ========== 上下文判断 ==========

    /** 未登录 → businessUnitType 为 null，isAdmin 为 false */
    @Test
    void contextWhenUnauthenticated() {
        assertNull(BusinessUnitContext.getCurrentBusinessUnitType());
        assertFalse(BusinessUnitContext.isAdmin());
    }

    /** ADMIN 角色 → isAdmin = true */
    @Test
    void adminRoleRecognized() {
        authenticate(null, "ROLE_ADMIN");
        assertTrue(BusinessUnitContext.isAdmin());
    }

    /** 数据库 role_key 为 admin 时会生成 ROLE_admin，也应识别为 admin */
    @Test
    void adminRoleRecognizedIgnoreCase() {
        authenticate(null, "ROLE_admin");
        assertTrue(BusinessUnitContext.isAdmin());
    }

    /** 拥有 *:*:* 通配权限 → 亦视为 admin */
    @Test
    void wildcardPermissionTreatedAsAdmin() {
        authenticate(null, "*:*:*");
        assertTrue(BusinessUnitContext.isAdmin());
    }

    /** BU_STAFF → 返回 businessUnitType，非 admin */
    @Test
    void buStaffReturnsBusinessUnit() {
        authenticate("COMMERCIAL", "ROLE_BU_STAFF");
        assertEquals("COMMERCIAL", BusinessUnitContext.getCurrentBusinessUnitType());
        assertFalse(BusinessUnitContext.isAdmin());
    }

    // ========== @DataScope 解析 ==========

    /** Mapper 方法标注 @DataScope → 能反射到注解 */
    @Test
    void resolveDataScopeFromAnnotatedMethod() {
        DataScope scope = interceptor.resolveDataScope(
                SampleMapper.class.getName() + ".listScoped");
        assertNotNull(scope);
        assertEquals("q", scope.alias());
        assertEquals("business_unit_type", scope.column());
    }

    /** 未标注 @DataScope → 返回 null，且缓存占位不为 null 引用 */
    @Test
    void resolveDataScopeReturnsNullForPlainMethod() {
        DataScope scope = interceptor.resolveDataScope(
                SampleMapper.class.getName() + ".listPlain");
        assertNull(scope);
        // 第二次调用走缓存路径，结果一致
        assertNull(interceptor.resolveDataScope(SampleMapper.class.getName() + ".listPlain"));
    }

    /** 不存在的类 → 返回 null，不抛异常 */
    @Test
    void resolveDataScopeSilentOnMissingClass() {
        assertNull(interceptor.resolveDataScope("com.no.such.Mapper.method"));
    }

    // ========== 辅助 ==========

    /** 将带有 details(businessUnitType) 和 authorities 的 Authentication 放入 SecurityContext */
    private void authenticate(String businessUnitType, String... authorities) {
        List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("u", "N/A", auths);
        if (businessUnitType != null) {
            token.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
        }
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /** 测试用 Mapper，仅用于反射解析 @DataScope */
    interface SampleMapper {
        @DataScope(alias = "q")
        List<Object> listScoped();

        List<Object> listPlain();
    }
}
