package ru.murad.myvpn.service;

import ru.murad.myvpn.model.VpnDeliveryFailureCode;
import java.time.Instant;
import java.util.List;

public interface VpnDeliveryTransactionService {
    int markExhaustedDeliveries(Instant now, int limit);
    List<ClaimedVpnDelivery> claim(Instant now, int limit);
    boolean delivered(ClaimedVpnDelivery delivery, Long messageId, Instant now);
    boolean retry(ClaimedVpnDelivery delivery, VpnDeliveryFailureCode code, Instant now, Instant nextAttemptAt);
    boolean manualReview(ClaimedVpnDelivery delivery, VpnDeliveryFailureCode code, Instant now);
}
