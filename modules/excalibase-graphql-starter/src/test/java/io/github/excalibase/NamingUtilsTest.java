package io.github.excalibase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NamingUtilsTest {

    @Test
    void toLowerCamelCase_snakeCase() {
        assertEquals("orderItems", NamingUtils.toLowerCamelCase("order_items"));
    }

    @Test
    void toLowerCamelCase_singleWord() {
        assertEquals("customer", NamingUtils.toLowerCamelCase("customer"));
    }

    @Test
    void toLowerCamelCase_multipleUnderscores() {
        assertEquals("highValueOrders", NamingUtils.toLowerCamelCase("high_value_orders"));
    }

    @Test
    void toLowerCamelCase_null() {
        assertNull(NamingUtils.toLowerCamelCase(null));
    }

    @Test
    void capitalize_snakeCase() {
        assertEquals("OrderItems", NamingUtils.capitalize("order_items"));
    }

    @Test
    void capitalize_singleWord() {
        assertEquals("Customer", NamingUtils.capitalize("customer"));
    }

    @Test
    void capitalize_multipleUnderscores() {
        assertEquals("HighValueOrders", NamingUtils.capitalize("high_value_orders"));
    }

    @Test
    void capitalize_null() {
        assertNull(NamingUtils.capitalize(null));
    }

    @Test
    void camelToSnakeCase_simple() {
        assertEquals("order_items", NamingUtils.camelToSnakeCase("orderItems"));
    }

    @Test
    void camelToSnakeCase_pascalCase() {
        assertEquals("order_items", NamingUtils.camelToSnakeCase("OrderItems"));
    }

    @Test
    void camelToSnakeCase_alreadySnake() {
        assertEquals("customer", NamingUtils.camelToSnakeCase("customer"));
    }

    @Test
    void camelToSnakeCase_null() {
        assertNull(NamingUtils.camelToSnakeCase(null));
    }

}
