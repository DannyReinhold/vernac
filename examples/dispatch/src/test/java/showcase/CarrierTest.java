package showcase;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.http.HttpAdapterClient;
import org.vernac.runtime.ports.RemoteIntegrationException;
import showcase.carrier.adapter.outbound.CarrierHttp;
import showcase.carrier.domain.*;
import showcase.integration.CarrierSimulator;

class CarrierTest {
  @Test
  void generatedAclHandlesExpansionAndReportsRemoteFailure() throws Exception {
    try (var server = new CarrierSimulator();
        var transport = HttpClient.newHttpClient()) {
      var carrier =
          new CarrierHttp(
              new HttpAdapterClient(transport, Duration.ofSeconds(3), 8192), server.baseUri());
      var shipment = Shipment.of(Destination.of("Bremen"), ParcelWeight.of(new BigDecimal("20")));
      assertThat(carrier.quote(shipment).price().amount()).isEqualByComparingTo("7.00");
      server.failNextRequest();
      assertThatThrownBy(() -> carrier.quote(shipment))
          .isInstanceOf(RemoteIntegrationException.class);
      assertThat(carrier.quote(shipment).reference().string()).isEqualTo("LOCAL-Bremen");
    }
  }

  @Test
  void applicationYamlResolvesSimulatorPortWithoutUriBean() throws Exception {
    try (var server = new CarrierSimulator();
        var transport = HttpClient.newHttpClient();
        var spring =
            new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      var yaml =
          new org.springframework.boot.env.YamlPropertySourceLoader()
              .load(
                  "application",
                  new org.springframework.core.io.ClassPathResource("application.yaml"));
      yaml.forEach(source -> spring.getEnvironment().getPropertySources().addLast(source));
      spring
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "test",
                  java.util.Map.of(
                      "dispatch.carrier.simulator-port",
                      server.baseUri().getPort(),
                      "CARRIER_BASE_URL",
                      "http://127.0.0.1:${dispatch.carrier.simulator-port}")));
      spring.registerBean(
          HttpAdapterClient.class,
          () -> new HttpAdapterClient(transport, Duration.ofSeconds(3), 8192));
      spring.register(CarrierHttp.class);
      spring.refresh();
      var shipment = Shipment.of(Destination.of("Bremen"), ParcelWeight.of(new BigDecimal("20")));
      assertThat(spring.getBean(CarrierHttp.class).quote(shipment).price().amount())
          .isEqualByComparingTo("7.00");
      assertThat(spring.getBeanNamesForType(java.net.URI.class)).isEmpty();
    }
  }
}
