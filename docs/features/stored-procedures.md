# Stored Procedures

Excalibase GraphQL automatically discovers stored procedures in your database and exposes them as GraphQL mutations. Both PostgreSQL and MySQL backends are supported.

## How It Works

1. At startup, Excalibase introspects the database for stored procedures
2. Each procedure becomes a `call{ProcedureName}` mutation in the GraphQL schema
3. IN parameters become GraphQL arguments; OUT/INOUT parameters are returned as JSON
4. The mutation returns a JSON string — parse it on the client to access OUT values

## Defining a Stored Procedure

### PostgreSQL

```sql
CREATE OR REPLACE PROCEDURE get_customer_order_count(
    IN  p_customer_id BIGINT,
    OUT p_count       INT
)
LANGUAGE plpgsql AS $$
BEGIN
    SELECT COUNT(*) INTO p_count
    FROM orders
    WHERE customer_id = p_customer_id;
END;
$$;
```

### MySQL

```sql
CREATE PROCEDURE get_customer_order_count(
    IN  p_customer_id BIGINT,
    OUT p_count       INT
)
BEGIN
    SELECT COUNT(*) INTO p_count
    FROM orders
    WHERE customer_id = p_customer_id;
END;
```

## Calling from GraphQL

Excalibase generates a mutation named `call{Schema}{ProcedureName}` (camel-cased):

```graphql
mutation {
  callHanaGetCustomerOrderCount(p_customer_id: 1)
}
```

**Response:**

```json
{
  "data": {
    "callHanaGetCustomerOrderCount": "{\"p_count\":3}"
  }
}
```

Parse the result:

```js
const raw = data.callHanaGetCustomerOrderCount;
const result = JSON.parse(raw);
console.log(result.p_count); // 3
```

## Parameter Types

| Parameter Mode | Behavior |
|---------------|----------|
| `IN` | Becomes a required GraphQL argument |
| `OUT` | Omitted from arguments; included in the JSON result string |
| `INOUT` | Both an argument and included in the JSON result |

## Example: Transfer Funds

This example shows a realistic procedure with business logic, error handling, and both IN and OUT parameters.

### Database Setup

```sql
CREATE TABLE wallets (
    wallet_id  BIGINT PRIMARY KEY,
    owner_name VARCHAR(100) NOT NULL,
    balance    NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (balance >= 0)
);

INSERT INTO wallets VALUES (1, 'Alice', 1000.00), (2, 'Bob', 500.00), (3, 'Charlie', 10.00);
```

### PostgreSQL Procedure

```sql
CREATE OR REPLACE PROCEDURE transfer_funds(
    IN  p_from_wallet_id BIGINT,
    IN  p_to_wallet_id   BIGINT,
    IN  p_amount         NUMERIC(15,2),
    OUT p_status         TEXT
)
LANGUAGE plpgsql AS $$
DECLARE
    v_balance NUMERIC(15,2);
BEGIN
    SELECT balance INTO v_balance
    FROM wallets
    WHERE wallet_id = p_from_wallet_id
    FOR UPDATE;

    IF v_balance IS NULL THEN
        p_status := 'ERROR: Source wallet not found'; RETURN;
    END IF;

    IF v_balance < p_amount THEN
        p_status := 'ERROR: Insufficient funds (balance=' || v_balance
                    || ', requested=' || p_amount || ')'; RETURN;
    END IF;

    UPDATE wallets SET balance = balance - p_amount WHERE wallet_id = p_from_wallet_id;
    UPDATE wallets SET balance = balance + p_amount WHERE wallet_id = p_to_wallet_id;
    p_status := 'SUCCESS';
END;
$$;
```

### MySQL Procedure

```sql
CREATE PROCEDURE transfer_funds(
    IN  p_from_wallet_id BIGINT,
    IN  p_to_wallet_id   BIGINT,
    IN  p_amount         DECIMAL(15,2),
    OUT p_status         VARCHAR(200)
)
BEGIN
    DECLARE v_balance DECIMAL(15,2);

    SELECT balance INTO v_balance FROM wallets
    WHERE wallet_id = p_from_wallet_id FOR UPDATE;

    IF v_balance IS NULL THEN
        SET p_status = 'ERROR: Source wallet not found';
    ELSEIF v_balance < p_amount THEN
        SET p_status = CONCAT('ERROR: Insufficient funds (balance=', v_balance,
                              ', requested=', p_amount, ')');
    ELSE
        UPDATE wallets SET balance = balance - p_amount WHERE wallet_id = p_from_wallet_id;
        UPDATE wallets SET balance = balance + p_amount WHERE wallet_id = p_to_wallet_id;
        SET p_status = 'SUCCESS';
    END IF;
END;
```

### Happy Path — Sufficient Funds

```graphql
mutation {
  callHanaTransferFunds(
    p_from_wallet_id: 1
    p_to_wallet_id: 2
    p_amount: 200.00
  )
}
```

```json
{
  "data": {
    "callHanaTransferFunds": "{\"p_status\":\"SUCCESS\"}"
  }
}
```

Alice's balance drops from 1000 to 800; Bob's rises from 500 to 700.

### Unhappy Path — Insufficient Funds

```graphql
mutation {
  callHanaTransferFunds(
    p_from_wallet_id: 3
    p_to_wallet_id: 1
    p_amount: 500.00
  )
}
```

```json
{
  "data": {
    "callHanaTransferFunds": "{\"p_status\":\"ERROR: Insufficient funds (balance=10.00, requested=500.00)\"}"
  }
}
```

Charlie has only 10.00 — the procedure rejects the transfer and no balances change. The `CHECK (balance >= 0)` constraint on the `wallets` table also acts as a database-level safety net.

### Client-Side Handling

```js
import { gql, GraphQLClient } from 'graphql-request';

const client = new GraphQLClient('http://localhost:10000/graphql');

async function transferFunds(fromId, toId, amount) {
  const data = await client.request(gql`
    mutation($from: Int!, $to: Int!, $amount: Float!) {
      callHanaTransferFunds(
        p_from_wallet_id: $from
        p_to_wallet_id: $to
        p_amount: $amount
      )
    }
  `, { from: fromId, to: toId, amount });

  const result = JSON.parse(data.callHanaTransferFunds);

  if (result.p_status === 'SUCCESS') {
    console.log('Transfer completed');
  } else {
    console.error('Transfer failed:', result.p_status);
  }
}
```

## Schema Introspection

You can verify a procedure is available via GraphQL introspection:

```graphql
{
  __type(name: "Mutation") {
    fields {
      name
      args {
        name
        type { name kind }
      }
    }
  }
}
```

Look for fields starting with `call{Schema}` in the Mutation type (e.g., `callHanaTransferFunds`, `callExcalibaseGetCustomerOrderCount`).

## Limitations

- Only `PROCEDURE` objects are supported — scalar functions are not exposed as mutations
- OUT parameter values are serialized as a single JSON string (not as individual GraphQL fields)
- Stored procedure discovery happens at startup; a server restart is required after adding new procedures
