package ru.murad.myvpn.repository;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Optional;

public final class DatabaseConstraintExtractor {

    private DatabaseConstraintExtractor() {
    }

    public static Optional<String> extract(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return Optional.of(violation.getConstraintName());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
