package showcase.integration;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.vernac.runtime.http.HttpAdapterClient;

@Configuration
public class IntegrationConfiguration {
  @Bean(destroyMethod = "close")
  CarrierSimulator carrierSimulator(@Value("${dispatch.carrier.simulator-port}") int port)
      throws java.io.IOException {
    return new CarrierSimulator(port);
  }

  @Bean(destroyMethod = "close")
  HttpClient transport() {
    return HttpClient.newHttpClient();
  }

  @Bean
  HttpAdapterClient http(HttpClient transport) {
    return new HttpAdapterClient(transport, Duration.ofSeconds(3), 8192);
  }
}
