package ru.murad.myvpn.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.service.impl.FakePaymentVerificationPolicy;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class ProviderPaymentValidatorTest {
 private static final Instant NOW=Instant.parse("2026-07-25T10:00:00Z");
 private final ProviderPaymentValidator validator = new ProviderPaymentValidator(
   new ProviderPaymentVerificationPolicyRegistry(List.of(new FakePaymentVerificationPolicy())),
   new PaymentProperties(PaymentProviderType.FAKE,Duration.ofHours(1), URI.create("https://example.invalid"),true));
 private final PreparedPaymentVerification expected = new PreparedPaymentVerification(
   java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), PaymentProviderType.FAKE,"p",
   new BigDecimal("10.00"),"RUB",java.util.UUID.randomUUID(),"T","Tariff",30,
   PaymentStatus.PENDING,java.util.UUID.randomUUID());
 @Test void verificationOutcomesIncludeAmbiguityAndTerminal() {
  assertThat(PaymentVerificationOutcome.values()).contains(PaymentVerificationOutcome.AMBIGUOUS_PAYMENT_STATE, PaymentVerificationOutcome.TERMINAL);
 }
 @Test void validPendingAndSucceededPass() {
  var created=NOW.minusSeconds(10); var id=expected.paymentOrderId();
  var p=new ProviderPayment("p",ProviderPaymentStatus.PENDING,false,new BigDecimal("10"),"RUB","fake",id,null,created,null);
  org.assertj.core.api.Assertions.assertThatCode(()->validator.validate(expected,p,NOW)).doesNotThrowAnyException();
  var succeeded=new ProviderPayment("p",ProviderPaymentStatus.SUCCEEDED,true,new BigDecimal("10.0"),"RUB","fake",id,null,created,NOW);
  org.assertj.core.api.Assertions.assertThatCode(()->validator.validate(expected,succeeded,NOW)).doesNotThrowAnyException();
 }
 @ParameterizedTest @ValueSource(strings={"rub","USD",""}) void currencyIsStrict(String c){
  var p=new ProviderPayment("p",ProviderPaymentStatus.PENDING,false,new BigDecimal("10"),c,"fake",expected.paymentOrderId(),null,NOW,null);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->validator.validate(expected,p,NOW)).isInstanceOf(ProviderPaymentValidationException.class).hasMessage("Provider payment validation failed");
 }
 @Test void paidStatusMismatchIsManual(){
  var p=new ProviderPayment("p",ProviderPaymentStatus.PENDING,true,new BigDecimal("10"),"RUB","fake",expected.paymentOrderId(),null,NOW,null);
  org.assertj.core.api.Assertions.assertThatThrownBy(()->validator.validate(expected,p,NOW)).isInstanceOf(ProviderPaymentValidationException.class);
 }
}
