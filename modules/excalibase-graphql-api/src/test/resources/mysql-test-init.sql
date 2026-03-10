-- Init script run by MySQLContainer before Spring context starts.
-- Tables must exist at startup so the GraphQL schema is non-empty.

CREATE TABLE IF NOT EXISTS customer (
    customer_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    first_name  VARCHAR(45)  NOT NULL,
    last_name   VARCHAR(45)  NOT NULL,
    email       VARCHAR(100),
    active      TINYINT(1)   DEFAULT 1,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS orders (
    order_id    BIGINT       AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT       NOT NULL,
    total       DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20)  DEFAULT 'pending',
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);
