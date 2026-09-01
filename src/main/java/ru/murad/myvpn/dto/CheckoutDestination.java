package ru.murad.myvpn.dto;
import java.net.URI;
public sealed interface CheckoutDestination permits CheckoutDestination.RedirectUrl {
 record RedirectUrl(URI url) implements CheckoutDestination { public RedirectUrl { if(url==null) throw new IllegalArgumentException("Redirect URL is required"); } }
}
