package com.sanhua.marketingcost.security;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.sanhua.marketingcost.annotation.DataScope;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务单元数据隔离拦截器（MyBatis Plus InnerInterceptor）。
 * <p>
 * 拦截被 {@link DataScope} 注解的 Mapper 查询方法，自动向 SQL 的 WHERE 子句追加：
 * <pre>
 *   AND [alias.]business_unit_type = '当前用户的业务单元'
 * </pre>
 * <p>
 * 规则（v1.3 方案 C 修订：admin 不再豁免，所有登录角色按选定单元过滤）：
 * <ul>
 *   <li>未标注 {@link DataScope} → 完全不干预，原 SQL 不变</li>
 *   <li>SecurityContext 中 businessUnitType 为 null → 不追加（未登录 / 系统级接口）</li>
 *   <li>否则追加 EqualsTo 条件（admin 登录选了哪个单元就按哪个过滤）</li>
 * </ul>
 * <p>
 * 注：为与 MyBatis Plus 的 {@code InterceptorIgnoreHelper} 协同，
 * 若方法上标注 {@code @InterceptorIgnore(dataPermission = true)} 亦会跳过本拦截器。
 */
public class BusinessUnitInterceptor implements InnerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BusinessUnitInterceptor.class);

    /** MappedStatement id → 对应方法的 @DataScope 注解（null 表示无注解，避免重复反射） */
    private final ConcurrentHashMap<String, DataScope> scopeCache = new ConcurrentHashMap<>();

    /** 占位对象：表示已解析且无 @DataScope 注解 */
    private static final DataScope NONE = new DataScope() {
        @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return DataScope.class; }
        @Override public String alias() { return ""; }
        @Override public String column() { return "__none__"; }
        @Override public boolean includeShared() { return false; }
    };

    /** SELECT 拦截点 */
    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        // 1. 若 MP 显式忽略数据权限，直接放行
        if (InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())) {
            return;
        }
        // 2. 解析方法上的 @DataScope 注解
        DataScope scope = resolveDataScope(ms.getId());
        if (scope == null) {
            return;
        }
        // 3. v1.3 方案 C：admin 不再豁免，统一按当前登录单元过滤
        //    仅当 businessUnitType 为空（未登录 / 系统级接口）时才跳过
        String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
        if (businessUnitType == null || businessUnitType.isEmpty()) {
            return;
        }
        // 4. 重写 SQL
        String originalSql = boundSql.getSql();
        try {
            String newSql = appendBusinessUnitCondition(
                    originalSql, scope.alias(), scope.column(), businessUnitType, scope.includeShared());
            if (!newSql.equals(originalSql)) {
                PluginUtils.mpBoundSql(boundSql).sql(newSql);
                if (log.isDebugEnabled()) {
                    log.debug("业务单元数据隔离已追加: method={}, businessUnitType={}, sql={}",
                            ms.getId(), businessUnitType, newSql);
                }
            }
        } catch (JSQLParserException e) {
            // 解析失败则放弃改写，记录告警，避免影响业务。
            log.warn("业务单元数据隔离 SQL 解析失败，放弃改写: method={}, sql={}, err={}",
                    ms.getId(), originalSql, e.getMessage());
        }
    }

    /** UPDATE / DELETE / 其他语句不做干预（本次仅处理查询） */
    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        // no-op
    }

    /**
     * 根据 MappedStatement id（形如 {@code com.xx.Mapper.methodName}）反射解析 {@link DataScope}。
     * 结果会被缓存到 {@link #scopeCache}，避免每次查询都反射。
     *
     * @return 方法上的 @DataScope 注解；若无则返回 null
     */
    DataScope resolveDataScope(String statementId) {
        DataScope cached = scopeCache.get(statementId);
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        DataScope resolved = doResolve(statementId);
        scopeCache.put(statementId, resolved == null ? NONE : resolved);
        return resolved;
    }

    private DataScope doResolve(String statementId) {
        int lastDot = statementId.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String className = statementId.substring(0, lastDot);
        String methodName = statementId.substring(lastDot + 1);
        try {
            Class<?> clazz = Class.forName(className);
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.isAnnotationPresent(DataScope.class)) {
                    return m.getAnnotation(DataScope.class);
                }
            }
        } catch (ClassNotFoundException e) {
            // 非 Mapper 接口（例如内置 SQL）忽略
        }
        return null;
    }

    /**
     * 使用 jsqlparser 向 SQL 的 WHERE 条件追加业务单元过滤。
     * <ul>
     *   <li>{@code includeShared=false}：追加 {@code column = 'value'}</li>
     *   <li>{@code includeShared=true} ：追加 {@code (column = 'value' OR column IS NULL)}，
     *       允许看到 BU 列为 NULL 的跨单元共享行（如公共变量）</li>
     * </ul>
     * 支持 {@code PlainSelect} 与 {@code SetOperationList}（UNION 等）。
     * 包可见以便单元测试直接调用。
     */
    String appendBusinessUnitCondition(String sql, String alias, String column, String value,
                                       boolean includeShared)
            throws JSQLParserException {
        Statement stmt = CCJSqlParserUtil.parse(sql);
        if (!(stmt instanceof Select select)) {
            return sql;
        }
        applyToSelect(select, alias, column, value, includeShared);
        return select.toString();
    }

    /** 旧签名兜底，便于既有单测调用（默认不含共享行）。 */
    String appendBusinessUnitCondition(String sql, String alias, String column, String value)
            throws JSQLParserException {
        return appendBusinessUnitCondition(sql, alias, column, value, false);
    }

    private void applyToSelect(Select select, String alias, String column, String value, boolean includeShared) {
        if (select instanceof PlainSelect ps) {
            applyToPlainSelect(ps, alias, column, value, includeShared);
        } else if (select instanceof SetOperationList sol) {
            // UNION / INTERSECT 场景：递归处理每个子查询
            for (Select sub : sol.getSelects()) {
                applyToSelect(sub, alias, column, value, includeShared);
            }
        } else if (select instanceof ParenthesedSelect wrap) {
            applyToSelect(wrap.getSelect(), alias, column, value, includeShared);
        }
    }

    private void applyToPlainSelect(PlainSelect ps, String alias, String column, String value, boolean includeShared) {
        // v1.4 bug 修复 —— 派生子查询穿透：
        //
        // 遇到形如 {@code SELECT COUNT(1) FROM (SELECT 1 FROM tbl ...) t} 的 SQL，
        // 外层 FromItem 是派生表（ParenthesedSelect）。派生表 t 的列集合由内层
        // SELECT 决定，通常不含 business_unit_type。如果把 WHERE 加到外层，
        // 数据库会抛 "Unknown column 'business_unit_type' in 'where clause'"（见
        // BomManualItemMapper.countSummaryRows / BomManageItemMapper.countParentRows）。
        //
        // 修法：派生表情况下递归进子查询的 PlainSelect 加 WHERE，让条件落在真表上。
        // alias 保持原样传入 —— 如果子查询里用了同名别名（常见），条件正确；否则
        // alias 为空时按列名加，不影响子查询里唯一主表场景。
        //
        // JOIN 场景（派生表 + JOIN 别的表）罕见；若出现可在此处扩展同时加外层+子查询。
        if (ps.getFromItem() instanceof ParenthesedSelect derived) {
            applyToSelect(derived.getSelect(), alias, column, value, includeShared);
            return;
        }

        Column col = (alias == null || alias.isEmpty())
                ? new Column(column)
                : new Column(alias + "." + column);
        EqualsTo eq = new EqualsTo(col, new StringValue(value));
        Expression condition;
        if (includeShared) {
            // (column = 'value' OR column IS NULL) —— 允许跨 BU 共享行（例如公共变量）
            IsNullExpression isNull = new IsNullExpression();
            isNull.setLeftExpression(col);
            condition = new Parenthesis(new OrExpression(eq, isNull));
        } else {
            condition = eq;
        }
        Expression where = ps.getWhere();
        ps.setWhere(where == null ? condition : new AndExpression(where, condition));
    }
}
