package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompilationResult;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PortGeneratorTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Generiert Port-Interface, Schema-DTO und REST-Adapter")
    void shouldGeneratePortAndRestAdapterStructure() {
        String dsl = """
                package com.example.domain;
                
                port HolidayCalendarProvider {
                    schema HolidayResponseDto {
                        LocalDate date;
                        String name;
                    }
                
                    Optional<String> fetch() {
                        adapter rest {
                            GET "/api/holidays";
                            on 404 return Optional.empty();
                        }
                    }
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        List<String> typeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec().name())
                .toList();

        assertThat(typeNames).contains(
                "HolidayCalendarProvider",
                "HolidayResponseDto",
                "RestHolidayCalendarProviderFetchAdapter"
        );

        JavaFile portInterface = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("HolidayCalendarProvider"))
                .findFirst().orElseThrow();

        assertThat(portInterface.toString())
                .contains("public interface HolidayCalendarProvider")
                .contains("Optional<String> fetch();");

        JavaFile schemaDto = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("HolidayResponseDto"))
                .findFirst().orElseThrow();

        assertThat(schemaDto.toString())
                .contains("public class HolidayResponseDto")
                .contains("public HolidayResponseDto()")
                .contains("private LocalDate date;")
                .contains("public LocalDate date()");

        JavaFile restAdapter = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("RestHolidayCalendarProviderFetchAdapter"))
                .findFirst().orElseThrow();

        assertThat(restAdapter.toString())
                .contains("@Component")
                .contains("public class RestHolidayCalendarProviderFetchAdapter implements HolidayCalendarProvider")
                .contains("@Value(\"${vernac.outbound.holiday-calendar-provider.base-url:http://localhost:8080}\")")
                .contains("public RestHolidayCalendarProviderFetchAdapter(RestClient.Builder restClientBuilder,")
                .contains("this.restClient = Objects.requireNonNull(restClientBuilder, \"restClientBuilder must not be null\").baseUrl(baseUrl).build();");
    }

    @Test
    @DisplayName("Generiert Custom Delegate Interface bei Custom Adapter")
    void shouldGenerateCustomAdapterDelegate() {
        String dsl = """
                package com.example;
                
                value PdfDocument(String value);
                value InvoiceData(String value);
                
                port InvoiceGenerator {
                    PdfDocument generate(InvoiceData data) {
                        adapter custom InvoiceGeneratorDelegate;
                    }
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        List<String> typeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec().name())
                .toList();

        assertThat(typeNames).contains("InvoiceGenerator", "InvoiceGeneratorDelegate");

        JavaFile delegate = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("InvoiceGeneratorDelegate"))
                .findFirst().orElseThrow();

        assertThat(delegate.toString())
                .contains("public interface InvoiceGeneratorDelegate")
                .contains("PdfDocument generate(InvoiceData data);");
    }

    @Test
    @DisplayName("Verarbeitet URL-Parameter und generiert RestClient mit Catch-All Fehlerbehandlung")
    void shouldGenerateRestClientWithParamsAndCatchAllHandler() {
        String dsl = """
                package com.example;
                
                value CityName(String value);
                value Temperature(double celsius);
                
                port WeatherProvider {
                    schema WeatherDto {
                        double tempCelsius;
                    }
                
                    Optional<Temperature> fetchWeather(CityName city) {
                        adapter rest {
                            GET "/api/weather";
                            on 404 return Optional.empty();
                        }
                        mapping {
                            response.tempCelsius -> Temperature.celsius;
                        }
                    }
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile restAdapter = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("RestWeatherProviderFetchWeatherAdapter"))
                .findFirst().orElseThrow();

        String code = restAdapter.toString();

        // Prüft URL Zusammenbau und Query Parameter Extraction
        assertThat(code).contains(".uri(\"/api/weather?city={city}\", city.value())");

        // Prüft die Fehlerbehandlung
        assertThat(code).contains(".onStatus(HttpStatusCode.valueOf(404)::equals");
        assertThat(code).contains(".onStatus(HttpStatusCode::isError, (req, res) -> {");
        assertThat(code).contains("throw new RuntimeException(\"External API call failed with status: \" + res.getStatusCode());");

        // Prüft, dass Value Objects über .of() instanziiert werden
        assertThat(code).contains("return body != null ? Optional.of(Temperature.of(body.tempCelsius())) : Optional.empty();");
    }

    @Test
    @DisplayName("Mappt Infrastruktur-DTO in ein Entity via fromExternal")
    void shouldMapDtoToEntityUsingFromExternal() {
        String dsl = """
                package com.example;
                
                id ProjectId;
                value ProjectName(String value);
                
                entity Project [ProjectId] (ProjectName name);
                
                port ExternalProjectService {
                    schema ExternalProjectDto {
                        String extId;
                        String extTitle;
                    }
                
                    Project fetchProject(ProjectId id) {
                        adapter rest {
                            GET "/api/projects";
                        }
                        mapping {
                            response.extId -> Project.id;
                            response.extTitle -> Project.name;
                        }
                    }
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile restAdapter = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("RestExternalProjectServiceFetchProjectAdapter"))
                .findFirst().orElseThrow();

        String code = restAdapter.toString();

        // Prüft URL Zusammenbau mit Parameter
        assertThat(code).contains(".uri(\"/api/projects?id={id}\", id.value())");

        // Prüft, dass Entities über .fromExternal() instanziiert werden (id muss erstes Argument sein)
        assertThat(code).contains("return body != null ? Project.fromExternal(body.extId(), body.extTitle()) : null;");
    }
}