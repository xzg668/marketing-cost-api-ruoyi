package com.sanhua.marketingcost.security;

import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;

/** JSQLParser 4.9 把锁输出在排序和分页之前；按 MySQL 语法在每个查询末尾输出锁。 */
final class MySqlSelectDeParser extends SelectDeParser {
    MySqlSelectDeParser(StringBuilder buffer) {
        super(buffer);
        setExpressionVisitor(new ExpressionDeParser(this, buffer));
    }

    @Override
    public void visit(PlainSelect select) {
        var lockMode = select.getForMode();
        select.setForMode(null);
        try {
            super.visit(select);
        } finally {
            select.setForMode(lockMode);
        }
        if (lockMode == null) return;
        getBuffer().append(" FOR ").append(lockMode.getValue());
        if (select.getForUpdateTable() != null) getBuffer().append(" OF ").append(select.getForUpdateTable());
        if (select.getWait() != null) getBuffer().append(select.getWait());
        if (select.isNoWait()) getBuffer().append(" NOWAIT");
        else if (select.isSkipLocked()) getBuffer().append(" SKIP LOCKED");
    }
}
