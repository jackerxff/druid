/*
 * Copyright 1999-2011 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.stat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.JMException;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import com.alibaba.druid.Constants;
import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.proxy.jdbc.DataSourceProxy;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.Histogram;
import com.alibaba.druid.util.LRUCache;

public class JdbcDataSourceStat implements JdbcDataSourceStatMBean {

    private final static Log                                    LOG                     = LogFactory.getLog(JdbcDataSourceStat.class);

    private final String                                        name;
    private final String                                        url;
    private String                                              dbType;

    private final JdbcConnectionStat                            connectionStat          = new JdbcConnectionStat();
    private final JdbcResultSetStat                             resultSetStat           = new JdbcResultSetStat();
    private final JdbcStatementStat                             statementStat           = new JdbcStatementStat();

    private int                                                 maxSize                 = 1000 * 1;

    private ReentrantReadWriteLock                              lock                    = new ReentrantReadWriteLock();
    private final LinkedHashMap<String, JdbcSqlStat>            sqlStatMap;

    private final Histogram                                     connectionHoldHistogram = new Histogram(new long[] { //
                                                                                                        //
            1, 10, 100, 1000, 10 * 1000, //
            100 * 1000, 1000 * 1000
                                                                                                        //
                                                                                                        });

    private final ConcurrentMap<Long, JdbcConnectionStat.Entry> connections             = new ConcurrentHashMap<Long, JdbcConnectionStat.Entry>();

    private final AtomicLong                                    clobOpenCount           = new AtomicLong();

    private final AtomicLong                                    blobOpenCount           = new AtomicLong();

    public JdbcDataSourceStat(String name, String url){
        this(name, url, null);
    }

    public JdbcDataSourceStat(String name, String url, String dbType){
        this(name, url, dbType, null);
    }

    public JdbcDataSourceStat(String name, String url, String dbType, Properties connectProperties){
        this.name = name;
        this.url = url;
        this.dbType = dbType;

        if (connectProperties != null) {
            Object arg = connectProperties.get(Constants.DRUID_STAT_SQL_MAX_SIZE);

            if (arg == null) {
                arg = System.getProperty(Constants.DRUID_STAT_SQL_MAX_SIZE);
            }

            if (arg != null) {
                try {
                    maxSize = Integer.parseInt(arg.toString());
                } catch (NumberFormatException ex) {
                    LOG.error("maxSize parse error", ex);
                }
            }
        }

        sqlStatMap = new LRUCache<String, JdbcSqlStat>(maxSize, 16, 0.75f, false);
    }

    public void reset() {
        blobOpenCount.set(0);
        clobOpenCount.set(0);

        connectionStat.reset();
        statementStat.reset();
        resultSetStat.reset();
        connectionHoldHistogram.reset();

        lock.writeLock().lock();
        try {
            Iterator<Map.Entry<String, JdbcSqlStat>> iter = sqlStatMap.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, JdbcSqlStat> entry = iter.next();
                JdbcSqlStat stat = entry.getValue();
                if (stat.getExecuteCount() == 0 && stat.getRunningCount() == 0) {
                    stat.setRemoved(true);
                    iter.remove();
                } else {
                    stat.reset();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        for (JdbcConnectionStat.Entry connectionStat : connections.values()) {
            connectionStat.reset();
        }
    }

    public Histogram getConnectionHoldHistogram() {
        return connectionHoldHistogram;
    }

    public JdbcConnectionStat getConnectionStat() {
        return connectionStat;
    }

    public JdbcResultSetStat getResultSetStat() {
        return resultSetStat;
    }

    public JdbcStatementStat getStatementStat() {
        return statementStat;
    }

    @Override
    public String getConnectionUrl() {
        return url;
    }

    @Override
    public TabularData getSqlList() throws JMException {
        Map<String, JdbcSqlStat> sqlStatMap = this.getSqlStatMap();
        CompositeType rowType = JdbcSqlStat.getCompositeType();
        String[] indexNames = rowType.keySet().toArray(new String[rowType.keySet().size()]);

        TabularType tabularType = new TabularType("SqlListStatistic", "SqlListStatistic", rowType, indexNames);
        TabularData data = new TabularDataSupport(tabularType);

        for (Map.Entry<String, JdbcSqlStat> entry : sqlStatMap.entrySet()) {
            data.put(entry.getValue().getCompositeData());
        }

        return data;
    }

    public static StatFilter getStatFilter(DataSourceProxy dataSource) {
        for (Filter filter : dataSource.getProxyFilters()) {
            if (filter instanceof StatFilter) {
                return (StatFilter) filter;
            }
        }

        return null;
    }

    public JdbcSqlStat getSqlStat(int id) {
        return getSqlStat((long) id);
    }

    public JdbcSqlStat getSqlStat(long id) {
        lock.readLock().lock();
        try {
            for (Map.Entry<String, JdbcSqlStat> entry : this.sqlStatMap.entrySet()) {
                if (entry.getValue().getId() == id) {
                    return entry.getValue();
                }
            }

            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public final ConcurrentMap<Long, JdbcConnectionStat.Entry> getConnections() {
        return connections;
    }

    @Override
    public TabularData getConnectionList() throws JMException {
        CompositeType rowType = JdbcConnectionStat.Entry.getCompositeType();
        String[] indexNames = rowType.keySet().toArray(new String[rowType.keySet().size()]);

        TabularType tabularType = new TabularType("ConnectionListStatistic", "ConnectionListStatistic", rowType,
                                                  indexNames);
        TabularData data = new TabularDataSupport(tabularType);

        for (Map.Entry<Long, JdbcConnectionStat.Entry> entry : getConnections().entrySet()) {
            data.put(entry.getValue().getCompositeData());
        }

        return data;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, JdbcSqlStat> getSqlStatMap() {
        Map<String, JdbcSqlStat> map = new HashMap<String, JdbcSqlStat>();
        lock.readLock().lock();
        try {
            map.putAll(sqlStatMap);
        } finally {
            lock.readLock().unlock();
        }
        return map;
    }

    public JdbcSqlStat getSqlStat(String sql) {
        lock.readLock().lock();
        try {
            return sqlStatMap.get(sql);
        } finally {
            lock.readLock().unlock();
        }
    }

    public JdbcSqlStat createSqlStat(String sql) {
        lock.writeLock().lock();
        try {
            JdbcSqlStat sqlStat = sqlStatMap.get(sql);
            if (sqlStat == null) {
                sqlStat = new JdbcSqlStat(sql);
                sqlStat.setDbType(this.dbType);
                sqlStatMap.put(sql, sqlStat);
            }

            return sqlStat;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public long getConnectionActiveCount() {
        return this.connections.size();
    }

    @Override
    public long getConnectionConnectAliveMillis() {
        long nowNano = System.nanoTime();
        long aliveNanoSpan = this.getConnectionStat().getAliveTotal();

        for (JdbcConnectionStat.Entry connection : connections.values()) {
            aliveNanoSpan += nowNano - connection.getEstablishNano();
        }
        return aliveNanoSpan / (1000 * 1000);
    }

    public long getConnectionConnectAliveMillisMax() {
        long max = this.getConnectionStat().getAliveNanoMax();

        long nowNano = System.nanoTime();
        for (JdbcConnectionStat.Entry connection : connections.values()) {
            long connectionAliveNano = nowNano - connection.getEstablishNano();
            if (connectionAliveNano > max) {
                max = connectionAliveNano;
            }
        }
        return max / (1000 * 1000);
    }

    public long getConnectionConnectAliveMillisMin() {
        long min = this.getConnectionStat().getAliveNanoMin();

        long nowNano = System.nanoTime();
        for (JdbcConnectionStat.Entry connection : connections.values()) {
            long connectionAliveNano = nowNano - connection.getEstablishNano();
            if (connectionAliveNano < min || min == 0) {
                min = connectionAliveNano;
            }
        }
        return min / (1000 * 1000);
    }

    public long[] getConnectionHistogramRanges() {
        return connectionStat.getHistogramRanges();
    }

    public long[] getConnectionHistogramValues() {
        return connectionStat.getHistorgramValues();
    }

    public long getClobOpenCount() {
        return clobOpenCount.get();
    }

    public void incrementClobOpenCount() {
        clobOpenCount.incrementAndGet();
    }

    public long getBlobOpenCount() {
        return blobOpenCount.get();
    }

    public void incrementBlobOpenCount() {
        blobOpenCount.incrementAndGet();
    }

}
