package ru.murad.myvpn.config;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Holds a PostgreSQL session lock while this bot owns Telegram getUpdates. */
@Component
@Profile("!test")
public class TelegramLongPollingInstanceLock {

    private final Connection connection;

    public TelegramLongPollingInstanceLock(DataSource dataSource, TelegramProperties telegramProperties)
            throws SQLException {
        this.connection = dataSource.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(
                "select pg_try_advisory_lock(hashtextextended(?, 0))")) {
            statement.setString(1, telegramProperties.botToken());
            if (!statement.executeQuery().next() || !statement.getResultSet().getBoolean(1)) {
                closeConnection();
                throw new IllegalStateException(
                        "Another application instance already owns Telegram long polling for this bot");
            }
        } catch (SQLException | RuntimeException exception) {
            closeConnection();
            throw exception;
        }
    }

    @PreDestroy
    void release() {
        closeConnection();
    }

    private void closeConnection() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Closing a database session releases the advisory lock regardless.
        }
    }
}
