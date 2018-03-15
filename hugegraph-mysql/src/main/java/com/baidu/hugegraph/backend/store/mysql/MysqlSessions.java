/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.store.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.store.BackendSessionPool;
import com.baidu.hugegraph.config.HugeConfig;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;

public class MysqlSessions extends BackendSessionPool {

    private static final Logger LOG = Log.logger(MysqlStore.class);

    private HugeConfig config;
    private String database;

    public MysqlSessions(HugeConfig config, String database) {
        this.config = config;
        this.database = database;
    }

    /**
     * Connect DB with specified database, if failed will not reconnect
     */
    public void open() throws SQLException {
        Connection conn = null;
        try {
            conn = this.open(false);
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
    }

    /**
     * Connect DB with specified database
     */
    private Connection open(Boolean autoReconnect) throws SQLException {
        String url = this.config.get(MysqlOptions.JDBC_URL);
        if (url.endsWith("/")) {
            url = String.format("%s%s", url, this.database);
        } else {
            url = String.format("%s/%s", url, this.database);
        }

        Integer maxTimes = this.config.get(MysqlOptions.JDBC_RECONNECT_MAX_TIMES);
        Integer interval = this.config.get(MysqlOptions.JDBC_RECONNECT_INTERVAL);

        URIBuilder uriBuilder = new URIBuilder();
        uriBuilder.setPath(url)
                  .setParameter("autoReconnect", autoReconnect.toString())
                  .setParameter("maxReconnects", maxTimes.toString())
                  .setParameter("initialTimeout", interval.toString());

        return this.connect(uriBuilder.toString());
    }

    private Connection connect(String url) throws SQLException {
        String driverName = this.config.get(MysqlOptions.JDBC_DRIVER);
        String username = this.config.get(MysqlOptions.JDBC_USERNAME);
        String password = this.config.get(MysqlOptions.JDBC_PASSWORD);
        try {
            // Register JDBC driver
            Class.forName(driverName);
        } catch (ClassNotFoundException e) {
            throw new BackendException("Invalid driver class '%s'",
                                       driverName);
        }
        return DriverManager.getConnection(url, username, password);
    }

    @Override
    protected final synchronized Session newSession() {
        return new Session();
    }

    public final synchronized Session session() {
        return (Session) super.getOrNewSession();
    }

    @Override
    protected void doClose() {
        // pass
    }

    public void checkSessionConnected() {
        MysqlSessions.Session session = this.session();
        E.checkState(session != null, "MySQL session has not been initialized");
        E.checkState(!session.closed(), "MySQL session has been closed");
    }

    public void createDatabase() {
        // Create database with non-database-session
        LOG.debug("Create database: {}", this.database);

        String sql = String.format("CREATE DATABASE IF NOT EXISTS %s " +
                                   "DEFAULT CHARSET utf8 COLLATE " +
                                   "utf8_general_ci;", this.database);

        try (Connection conn = this.openWithoutDB()) {
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new BackendException("Failed to create database '%s'",
                                       this.database);
        }
    }

    public void dropDatabase() {
        LOG.debug("Drop database: {}", this.database);

        String sql = String.format("DROP DATABASE IF EXISTS %s;",
                                   this.database);
        try (Connection conn = this.openWithoutDB()) {
            conn.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new BackendException("Failed to drop database '%s'",
                                       this.database);
        }
    }

    public boolean existsDatabase() {
        try (Connection conn = this.openWithoutDB();
             ResultSet result = conn.getMetaData().getCatalogs()) {
            while (result.next()) {
                String dbName = result.getString(1);
                if (dbName.equals(database)) {
                    return true;
                }
            }
        } catch (Exception e) {
            throw new BackendException("Failed to obtain mysql databases " +
                                       "info, please ensure it is ok", e);
        }
        return false;
    }

    /**
     * Connect DB without specified database
     */
    private Connection openWithoutDB() {
        String url = config.get(MysqlOptions.JDBC_URL);
        try {
            return connect(url);
        } catch (SQLException e) {
            throw new BackendException("Failed to access %s, " +
                                       "please ensure it is ok", url);
        }
    }

    public final class Session extends BackendSessionPool.Session {

        private Connection conn;
        private Map<String, PreparedStatement> statements;
        private boolean opened;
        private int count;

        public Session() {
            this.conn = null;
            this.statements = new HashMap<>();
            this.opened = false;
            this.count = 0;
            try {
                this.open();
            } catch (SQLException ignored) {
                // Ignore
            }
        }

        public void open() throws SQLException {
            if (this.conn != null && !this.conn.isClosed()) {
                return;
            }
            this.conn = MysqlSessions.this.open(true);
            this.opened = true;
        }

        @Override
        public void close() {
            assert this.closeable();
            if (this.conn == null) {
                return;
            }

            SQLException exception = null;
            for (PreparedStatement statement : this.statements.values()) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    exception = e;
                }
            }

            try {
                this.conn.close();
            } catch (SQLException e) {
                exception = e;
            }

            this.opened = false;
            if (exception != null) {
                throw new BackendException("Failed to close connection",
                                           exception);
            }
        }

        @Override
        public boolean closed() {
            return !this.opened;
        }

        @Override
        public void clear() {
            this.count = 0;
            SQLException exception = null;
            for (PreparedStatement statement : this.statements.values()) {
                try {
                    statement.clearBatch();
                } catch (SQLException e) {
                    exception = e;
                }
            }
            if (exception != null) {
                /*
                 * Will throw exception when the database connection error,
                 * we clear statements because clearBatch() failed
                 */
                this.statements = new HashMap<>();
            }
        }

        public void begin() throws SQLException {
            this.conn.setAutoCommit(false);
        }

        @Override
        public Integer commit() {
            int updated = 0;
            try {
                for (PreparedStatement statement : this.statements.values()) {
                    updated += IntStream.of(statement.executeBatch()).sum();
                }
                this.conn.commit();
                this.clear();
            } catch (SQLException e) {
                throw new BackendException("Failed to commit", e);
            }
            return updated;
        }

        public void rollback() {
            this.clear();
            try {
                this.conn.rollback();
            } catch (SQLException e) {
                throw new BackendException("Failed to rollback", e);
            }
        }

        @Override
        public boolean hasChanges() {
            return this.count > 0;
        }

        public ResultSet select(String sql) throws SQLException {
            return this.conn.createStatement().executeQuery(sql);
        }

        public boolean execute(String sql) throws SQLException {
            return this.conn.createStatement().execute(sql);
        }

        public void add(PreparedStatement statement) {
            try {
                // Add a row to statement
                statement.addBatch();
                this.count++;
            } catch (SQLException e) {
                throw new BackendException("Failed to add statement '%s' " +
                                           "to batch", e, statement);
            }
        }

        public PreparedStatement prepareStatement(String sqlTemplate)
                                                  throws SQLException {
            PreparedStatement statement = this.statements.get(sqlTemplate);
            if (statement == null) {
                statement = this.conn.prepareStatement(sqlTemplate);
                this.statements.putIfAbsent(sqlTemplate, statement);
            }
            return statement;
        }
    }
}