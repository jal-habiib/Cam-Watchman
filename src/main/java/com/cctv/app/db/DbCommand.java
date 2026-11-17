package com.cctv.app.db;

/**
 * A single-writer command queue for the database (plan p.3: "One writer thread owns all writes").
 *
 * <p>Commands are executed off the JavaFX thread on the repository's own connection. Retention /
 * reconciliation run on their own connections, which is safe in WAL mode.
 */
public interface DbCommand {

    void apply(java.sql.Connection connection) throws java.sql.SQLException;
}
