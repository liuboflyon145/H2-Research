/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.ddl;

import java.util.ArrayList;
import org.h2.command.CommandInterface;
import org.h2.command.Prepared;
import org.h2.engine.Database;
import org.h2.engine.Right;
import org.h2.engine.Session;
import org.h2.expression.Parameter;
import org.h2.result.ResultInterface;
import org.h2.table.Column;
import org.h2.table.Table;
import org.h2.util.StatementBuilder;
import org.h2.value.Value;
import org.h2.value.ValueInt;

/**
 * This class represents the statement
 * ANALYZE
 */
public class Analyze extends DefineCommand {

    /**
     * The sample size.
     */
    private int sampleRows;

    public Analyze(Session session) {
        super(session);
        sampleRows = session.getDatabase().getSettings().analyzeSample;
    }

    @Override
    public int update() {
        session.commit(true);
        session.getUser().checkAdmin();
        Database db = session.getDatabase();
        for (Table table : db.getAllTablesAndViews(false)) {
            analyzeTable(session, table, sampleRows, true);
        }
        return 0;
    }

    /**
     * Analyze this table.
     *
     * @param session the session
     * @param table the table
     * @param sample the number of sample rows
     * @param manual whether the command was called by the user
     */
    public static void analyzeTable(Session session, Table table, int sample,
            boolean manual) {
        if (!(table.getTableType().equals(Table.TABLE)) ||
                table.isHidden() || session == null) {
            return;
        }
        if (!manual) {
            if (session.getDatabase().isSysTableLocked()) {
                return;
            }
            if (table.hasSelectTrigger()) {
                return;
            }
        }
        if (table.isTemporary() && !table.isGlobalTemporary()
                && session.findLocalTempTable(table.getName()) == null) {
            return;
        }
        if (table.isLockedExclusively() && !table.isLockedExclusivelyBy(session)) {
            return;
        }
        if (!session.getUser().hasRight(table, Right.SELECT)) {
            return;
        }
        if (session.getCancel() != 0) {
            // if the connection is closed and there is something to undo
            return;
        }
        Database db = session.getDatabase();
        StatementBuilder buff = new StatementBuilder("SELECT ");
        Column[] columns = table.getColumns();
        for (Column col : columns) {
            buff.appendExceptFirst(", ");
            int type = col.getType();
            if (type == Value.BLOB || type == Value.CLOB) {
                // can not index LOB columns, so calculating
                // the selectivity is not required
                buff.append("MAX(100)");
            } else {
                buff.append("SELECTIVITY(").append(col.getSQL()).append(')');
            }
        }
        buff.append(" FROM ").append(table.getSQL());
        if (sample > 0) {
            buff.append(" LIMIT ? SAMPLE_SIZE ? ");
        }
        String sql = buff.toString(); //如: SELECT SELECTIVITY(ID), SELECTIVITY(NAME), SELECTIVITY(B) FROM PUBLIC.REGULARTABLETEST LIMIT 1 SAMPLE_SIZE 10000
        Prepared command = session.prepare(sql);
        if (sample > 0) {
            ArrayList<Parameter> params = command.getParameters();
            params.get(0).setValue(ValueInt.get(1));
            params.get(1).setValue(ValueInt.get(sample));
        }
        ResultInterface result = command.query(0);
        result.next();
        for (int j = 0; j < columns.length; j++) {
            int selectivity = result.currentRow()[j].getInt();
            columns[j].setSelectivity(selectivity);
        }
        if (manual) {
            db.updateMeta(session, table);
        } else {
            Session sysSession = db.getSystemSession();
            if (sysSession != session) {
                // if the current session is the system session
                // (which is the case if we are within a trigger)
                // then we can't update the statistics because
                // that would unlock all locked objects
                synchronized (db) {
                    db.updateMeta(sysSession, table);
                    sysSession.commit(true);
                }
            }
        }
    }

    public void setTop(int top) {
        this.sampleRows = top;
    }

    @Override
    public int getType() {
        return CommandInterface.ANALYZE;
    }

}
