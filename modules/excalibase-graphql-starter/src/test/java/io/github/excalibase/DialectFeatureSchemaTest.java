package io.github.excalibase;

import io.github.excalibase.schema.ExposedFunctions;
import io.github.excalibase.schema.IntrospectionHandler;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The schema offers the optional features the database declares and none other. */
class DialectFeatureSchemaTest {

    private static SchemaInfo users() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("users", "id", "integer");
        info.addColumn("users", "name", "varchar");
        info.addPrimaryKey("users", "id");
        return info;
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldNames(IntrospectionHandler handler, String type) {
        Map<String, Object> data = (Map<String, Object>) handler
                .execute("{ __type(name: \"" + type + "\") { fields { name } } }", Map.of()).get("data");
        Map<String, Object> described = (Map<String, Object>) data.get("__type");
        if (described == null) return List.of();
        return ((List<Map<String, Object>>) described.get("fields")).stream().map(f -> (String) f.get("name")).toList();
    }

    private static IntrospectionHandler handler(Set<DialectFeature> features) {
        return new IntrospectionHandler(users(), TableAccess.UNRESTRICTED, ExposedFunctions.NONE, false,
                FilterCapabilities.PORTABLE, features);
    }

    @Test
    void aDatabaseWithoutAggregatesOffersNoAggregateOrTotalCount() {
        IntrospectionHandler handler = handler(EnumSet.noneOf(DialectFeature.class));
        assertThat(fieldNames(handler, "Query")).contains("users").doesNotContain("usersAggregate");
        assertThat(fieldNames(handler, "UsersConnection")).doesNotContain("totalCount");
    }

    @Test
    void aDatabaseWithoutASubscriptionStreamOffersNoSubscription() {
        assertThat(fieldNames(handler(EnumSet.noneOf(DialectFeature.class)), "Subscription")).isEmpty();
    }

    @Test
    void aDatabaseThatDeclaresThemOffersBoth() {
        IntrospectionHandler handler = handler(EnumSet.of(DialectFeature.AGGREGATES, DialectFeature.SUBSCRIPTIONS));
        assertThat(fieldNames(handler, "Query")).contains("usersAggregate");
        assertThat(fieldNames(handler, "UsersConnection")).contains("totalCount");
        assertThat(fieldNames(handler, "Subscription")).containsExactly("usersChanges");
    }
}
