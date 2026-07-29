package ru.murad.myvpn.dto;

import java.net.URI;

public sealed interface CheckoutDestination
        permits CheckoutDestination.RedirectUrl, CheckoutDestination.TelegramInvoiceSent {

    record RedirectUrl(URI url) implements CheckoutDestination {
        public RedirectUrl {
            if (url == null) {
                throw new IllegalArgumentException("Redirect URL is required");
            }
        }
    }

    record TelegramInvoiceSent(int messageId) implements CheckoutDestination {
        public TelegramInvoiceSent {
            if (messageId <= 0) {
                throw new IllegalArgumentException("Telegram invoice message id must be positive");
            }
        }
    }
}
