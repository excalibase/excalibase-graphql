package io.github.excalibase.observability.actuator;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import io.micronaut.management.endpoint.annotation.Selector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** {@code /actuator/metrics} and {@code /actuator/metrics/{name}?tag=key:value}, in the shape Spring Boot answered. */
@Endpoint(id = "metrics", prefix = ActuatorEndpoint.PREFIX, defaultEnabled = true, defaultSensitive = false)
@Requires(condition = ExposedEndpointCondition.class)
public class MetricsActuator {

    private final MeterRegistry registry;

    MetricsActuator(MeterRegistry registry) {
        this.registry = registry;
    }

    @Read(produces = ActuatorEndpoint.JSON)
    public Map<String, Object> names() {
        return Map.of("names", registry.getMeters().stream().map(meter -> meter.getId().getName())
                .distinct().sorted().toList());
    }

    @Read(produces = ActuatorEndpoint.JSON)
    public HttpResponse<Map<String, Object>> metric(@Selector String name, @Nullable List<String> tag) {
        List<Tag> wanted = parseTags(tag);
        List<Meter> meters = registry.find(name).meters().stream()
                .filter(meter -> meter.getId().getTags().containsAll(wanted)).toList();
        if (meters.isEmpty()) {
            return HttpResponse.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", meters.get(0).getId().getDescription());
        body.put("baseUnit", meters.get(0).getId().getBaseUnit());
        body.put("measurements", measurements(meters));
        body.put("availableTags", availableTags(meters, wanted));
        return HttpResponse.ok(body);
    }

    private static List<Tag> parseTags(@Nullable List<String> pairs) {
        List<Tag> tags = new ArrayList<>();
        for (String pair : pairs == null ? List.<String>of() : pairs) {
            int colon = pair.indexOf(':');
            if (colon > 0) {
                tags.add(Tag.of(pair.substring(0, colon), pair.substring(colon + 1)));
            }
        }
        return tags;
    }

    /** One value per statistic across the series: the largest for MAX, the sum for the rest. */
    private static List<Map<String, Object>> measurements(List<Meter> meters) {
        Map<Statistic, Double> totals = new TreeMap<>();
        for (Meter meter : meters) {
            meter.measure().forEach(measurement -> totals.merge(measurement.getStatistic(), measurement.getValue(),
                    measurement.getStatistic() == Statistic.MAX ? Math::max : Double::sum));
        }
        return totals.entrySet().stream()
                .<Map<String, Object>>map(entry -> Map.of("statistic", entry.getKey().name(), "value", entry.getValue()))
                .toList();
    }

    private static List<Map<String, Object>> availableTags(List<Meter> meters, List<Tag> alreadyChosen) {
        Map<String, TreeSet<String>> values = new TreeMap<>();
        meters.forEach(meter -> meter.getId().getTags().stream()
                .filter(tag -> !alreadyChosen.contains(tag))
                .forEach(tag -> values.computeIfAbsent(tag.getKey(), key -> new TreeSet<>()).add(tag.getValue())));
        return values.entrySet().stream()
                .<Map<String, Object>>map(entry -> Map.of("tag", entry.getKey(), "values", List.copyOf(entry.getValue())))
                .collect(Collectors.toList());
    }
}
