package io.mats3.matssocket.quarkus;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link StatementClosingDataSource} - verifies that result sets are closed before their statement and
 * statements before their connection, whether the caller closes the statement itself or leaves it to the
 * connection, and that a misbehaving statement never keeps the connection from being closed.
 *
 * @author Thor Egil Kolltveit 2026-09-24 - thoregil@kolltveit.org
 */
public class Test_StatementClosingDataSource {

    /** Records every close() in the order it happened, across all fakes in a test. */
    private final List<String> _closeOrder = new ArrayList<>();

    @Test
    public void closingConnection_shouldCloseResultSetThenStatementThenConnection() throws SQLException {
        ResultSet rows = fakeResultSet("rows");
        PreparedStatement select = fakeStatement(PreparedStatement.class, "select", rows, null);
        Statement plain = fakeStatement(Statement.class, "plain", null, null);
        Connection real = fakeConnection(select, plain);

        Connection wrapped = StatementClosingDataSource.wrapConnection(real);
        PreparedStatement wrappedSelect = wrapped.prepareStatement("SELECT 1");
        Assert.assertSame(rows, wrappedSelect.executeQuery());
        wrapped.createStatement();

        wrapped.close();

        Assert.assertEquals(List.of("rows", "select", "plain", "connection"), _closeOrder);
    }

    @Test
    public void callerClosingStatement_shouldCloseItsResultSetFirst() throws SQLException {
        ResultSet rows = fakeResultSet("rows");
        PreparedStatement select = fakeStatement(PreparedStatement.class, "select", rows, null);
        Connection real = fakeConnection(select);

        Connection wrapped = StatementClosingDataSource.wrapConnection(real);
        PreparedStatement wrappedSelect = wrapped.prepareStatement("SELECT 1");
        wrappedSelect.executeQuery();
        wrappedSelect.close();
        wrapped.close();

        Assert.assertEquals(List.of("rows", "select", "connection"), _closeOrder);
    }

    @Test
    public void resourcesAlreadyClosedByCaller_shouldBeLeftAlone() throws SQLException {
        ResultSet rows = fakeResultSet("rows");
        PreparedStatement select = fakeStatement(PreparedStatement.class, "select", rows, null);
        Connection real = fakeConnection(select);

        Connection wrapped = StatementClosingDataSource.wrapConnection(real);
        PreparedStatement wrappedSelect = wrapped.prepareStatement("SELECT 1");
        wrappedSelect.executeQuery().close();
        wrappedSelect.close();
        wrapped.close();

        Assert.assertEquals(List.of("rows", "select", "connection"), _closeOrder);
    }

    @Test
    public void statementThatFailsToClose_shouldNotBlockConnectionClose() throws SQLException {
        Statement stubborn = fakeStatement(Statement.class, "stubborn", null, new SQLException("gone"));
        Statement broken = fakeStatement(Statement.class, "broken", null, new IllegalStateException("driver"));
        Connection real = fakeConnection(stubborn, broken);

        Connection wrapped = StatementClosingDataSource.wrapConnection(real);
        wrapped.createStatement();
        wrapped.createStatement();

        wrapped.close();

        Assert.assertEquals(List.of("connection"), _closeOrder);
    }

    // :: Fakes - reflective proxies so the JDBC interfaces need not be implemented in full.

    private ResultSet fakeResultSet(String name) {
        boolean[] closed = { false };
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return closed[0];
                        case "close":
                            // Every close is recorded, so a double close shows up in the order list.
                            closed[0] = true;
                            _closeOrder.add(name);
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    /**
     * @param failure
     *            thrown from {@code isClosed()} if unchecked, from {@code close()} if an {@link SQLException}.
     */
    private <T extends Statement> T fakeStatement(Class<T> type, String name, ResultSet resultSet,
            Exception failure) {
        boolean[] closed = { false };
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            if (failure instanceof RuntimeException) {
                                throw failure;
                            }
                            return closed[0];
                        case "executeQuery":
                        case "getResultSet":
                            return resultSet;
                        case "close":
                            if (failure instanceof SQLException) {
                                throw failure;
                            }
                            closed[0] = true;
                            _closeOrder.add(name);
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }));
    }

    private Connection fakeConnection(Statement... statementsInOrder) {
        int[] next = { 0 };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "prepareStatement":
                        case "createStatement":
                        case "prepareCall":
                            return statementsInOrder[next[0]++];
                        case "close":
                            _closeOrder.add("connection");
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
