package showcase.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.vernac.runtime.http.HttpAdapterClient;

@Configuration
public class IntegrationConfiguration {
  @Bean(destroyMethod = "close")
  CarrierSimulator carrierSimulator() throws java.io.IOException {
    return new CarrierSimulator();
  }

  @Bean
  URI baseUri(CarrierSimulator simulator) {
    return simulator.baseUri();
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
