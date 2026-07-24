package ru.murad.myvpn.exception;

public class AdministratorAccessDeniedException extends RuntimeException {

    public AdministratorAccessDeniedException() {
        super("Administrator access is required");
    }
}
