package ru.murad.myvpn.service;
import org.junit.jupiter.api.Test;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.service.impl.FakePaymentVerificationPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
class FakePaymentVerificationPolicyTest {
 @Test void policyIsFakeOnly() { assertThat(new FakePaymentVerificationPolicy().providerType()).isEqualTo(PaymentProviderType.FAKE); }
 @Test void nonFakeMethodIsRejectedWithoutSensitiveData() {
  var id=UUID.randomUUID(); var expected=new PreparedPaymentVerification(id,UUID.randomUUID(),PaymentProviderType.FAKE,"provider-secret",BigDecimal.TEN,"RUB",UUID.randomUUID(),"T","N",1,null,UUID.randomUUID());
  var actual=new ProviderPayment("provider-secret",ProviderPaymentStatus.PENDING,false,BigDecimal.TEN,"RUB","CARD",id,null,Instant.EPOCH,null);
  assertThatThrownBy(()->new ru.murad.myvpn.service.impl.FakePaymentVerificationPolicy().validate(expected,actual,Instant.EPOCH))
    .isInstanceOf(ProviderPaymentValidationException.class).hasMessageNotContaining("provider-secret").hasMessageNotContaining(id.toString());
 }
}
