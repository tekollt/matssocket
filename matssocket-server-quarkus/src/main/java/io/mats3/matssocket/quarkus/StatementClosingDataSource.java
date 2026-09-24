package io.mats3.matssocket.quarkus;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * {@link DataSource} wrapper that closes result sets before their statement, and statements before their
 * connection, when the owner is closed.
 * <p>
 * {@code ClusterStoreAndForward_SQL} takes a connection per operation and relies on {@link Statement#close()}
 * releasing the result set read from it and on {@link Connection#close()} releasing the statements opened on
 * it, which JDBC guarantees. Quarkus' pool, Agroal, does release them, but with its default
 * {@code quarkus.datasource.jdbc.detect-statement-leaks=true} it logs <i>"JDBC resources leaked: 1 ResultSet(s)
 * and 1 Statement(s)"</i> for every resource still open at that moment - which with MatsSocket means one warning
 * per message. Wrapping the pool's data source in this class makes those resources closed first, so the warning
 * never fires. Agroal only counts a wrapper it handed out as closed if that exact wrapper was closed, so the
 * result sets are captured as they are returned rather than looked up afterwards.
 * <p>
 * Usage:
 * <pre>{@code
 * ClusterStoreAndForward_SQL csaf = ClusterStoreAndForward_SQL.create(
 *         new StatementClosingDataSource(agroalDataSource), nodename);
 * }</pre>
 * The connections and statements handed out are {@link Proxy dynamic proxies} implementing only the standard
 * JDBC interface ({@link Connection}; {@link CallableStatement}, {@link PreparedStatement} or {@link Statement}),
 * so casts to vendor types will fail and {@code equals} compares the wrapped objects. Every call other than
 * {@code close()} is forwarded unchanged.
 *
 * @author Thor Egil Kolltveit 2026-09-24 - thoregil@kolltveit.org
 */
public class StatementClosingDataSource implements DataSource {

    private final DataSource _delegate;

    public StatementClosingDataSource(DataSource delegate) {
        _delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(_delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(_delegate.getConnection(username, password));
    }

    /**
     * Wraps a connection so that statements handed out by it are closed when the connection is closed.
     */
    static Connection wrapConnection(Connection connection) {
        // A pooled connection is checked out by one thread at a time; the list lives for one checkout.
        List<Statement> openStatements = new ArrayList<>();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("close".equals(name)) {
                        try {
                            closeAll(openStatements);
                        }
                        finally {
                            // Whatever a statement did on close, the connection must reach the pool.
                            openStatements.clear();
                        }
                    }
                    Object result = invoke(method, connection, args);
                    if (result instanceof Statement
                            && ("prepareStatement".equals(name) || "createStatement".equals(name)
                                    || "prepareCall".equals(name))) {
                        Statement wrapped = wrapStatement((Statement) result);
                        openStatements.add(wrapped);
                        return wrapped;
                    }
                    return result;
                });
    }

    /**
     * Wraps a statement so that the result sets it handed out are closed when the statement is closed.
     */
    static Statement wrapStatement(Statement statement) {
        List<ResultSet> openResultSets = new ArrayList<>();
        Class<?> face = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class
                : Statement.class;
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] { face },
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        try {
                            closeAll(openResultSets);
                        }
                        finally {
                            openResultSets.clear();
                        }
                    }
                    Object result = invoke(method, statement, args);
                    // getResultSet() returns the same set again; keep one entry per set.
                    if (result instanceof ResultSet && !openResultSets.contains(result)) {
                        openResultSets.add((ResultSet) result);
                    }
                    return result;
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void closeAll(List<? extends AutoCloseable> resources) {
        for (AutoCloseable resource : resources) {
            try {
                if (resource instanceof Statement && ((Statement) resource).isClosed()) {
                    continue;
                }
                if (resource instanceof ResultSet && ((ResultSet) resource).isClosed()) {
                    continue;
                }
                resource.close();
            }
            catch (Exception ignored) {
                // The owner is being closed regardless; nothing to do about a resource that refuses to close.
            }
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return _delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        _delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        _delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return _delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return _delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return _delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return _delegate.isWrapperFor(iface);
    }
}
