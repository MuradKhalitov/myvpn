package ru.murad.myvpn.service.impl;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.*;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.service.*;
@Service @RequiredArgsConstructor
public class PaymentCheckoutServiceImpl implements PaymentCheckoutService {
 private final PaymentOrderRepository orders; private final PaymentProviderRegistry providers; private final PaymentCheckoutTransactionService transactions; private final PaymentProperties properties; private final Clock clock;
 public PaymentCheckoutResult startCheckout(UUID accountId,String tariffCode){
  PreparedCheckout p=transactions.prepareCheckout(accountId,tariffCode,properties.provider(),properties.pendingTtl(),clock.instant());
  if(p.status()==PaymentStatus.PENDING)return result(p);
  try { return transactions.applyCreatedPayment(p,providers.resolve(p.provider()).createPayment(new CreatePaymentCommand(p.orderId(),p.idempotenceKey(),p.amount(),p.currency(),"MyVPN: "+p.tariffName(),properties.returnUrl(),Map.of("payment_order_id",p.orderId().toString()))),clock.instant()); }
  catch(PaymentProviderPermanentException e){transactions.markPermanentFailure(p,clock.instant());throw e;}
 }
 public ProviderPayment checkCurrentPayment(UUID accountId){var order=orders.findOpenByAccount(accountId).orElseThrow(PaymentNotFoundException::new);if(order.getProviderPaymentId()==null)throw new PaymentNotFoundException();return providers.resolve(order.getProvider()).getPayment(order.getProviderPaymentId());}
 private PaymentCheckoutResult result(PreparedCheckout p){if(p.confirmationUrl()==null)throw new PaymentProviderUncertainException("Checkout result is incomplete");return new PaymentCheckoutResult(p.orderId(),p.tariffName(),p.amount(),p.currency(),p.durationDays(),p.status(),new CheckoutDestination.RedirectUrl(p.confirmationUrl()),p.localExpiresAt());}
}
