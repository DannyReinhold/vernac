package org.vernac.example.home.energy.inbound;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/solar")
public class FakeSolarForecastController {

    @GetMapping("/forecast")
    public Map<String, Object> getForecast(@RequestParam("id") String id) {
        return Map.of(
                "location", "Bremen-Nord",
                "expectedYieldWh", 3600
        );
    }
}
