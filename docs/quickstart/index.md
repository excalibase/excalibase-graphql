# Quick Start Guide

Get up and running with Excalibase GraphQL in minutes! This guide will walk you through the essential steps to set up your GraphQL API.

## What You'll Learn

<div class="quickstart-grid">
<div class="quickstart-step">
<h3>📦 Installation</h3>
<p>Set up Excalibase GraphQL using Docker or local development environment.</p>
</div>

<div class="quickstart-step">
<h3>⚙️ Configuration</h3>
<p>Configure your PostgreSQL database connection and schema settings.</p>
</div>

<div class="quickstart-step">
<h3>🚀 First Query</h3>
<p>Run your first GraphQL query and explore the auto-generated schema.</p>
</div>
</div>

## Prerequisites

Before you begin, ensure you have:

- **Java 21+** installed
- **PostgreSQL 15+** database running
- **Docker** (for containerized setup)
- **Maven 3.8+** (for local development)

### Database Administrator Knowledge Required

**Important**: Excalibase GraphQL focuses exclusively on GraphQL API generation. For production use, you need knowledge of:

- **Database Performance**: Index creation, query optimization, and performance tuning
- **High Availability**: Database replica setup, failover configuration, and clustering
- **Security**: User permissions, access control, and database security best practices
- **Monitoring**: Database monitoring, logging, and health checks
- **Backup & Recovery**: Database backup strategies and disaster recovery procedures

**For GraphQL Subscriptions** (when available), additional PostgreSQL configuration is required:
- **Logical Replication**: Configure `wal_level = logical` in postgresql.conf
- **Replication Slots**: Create and manage replication slots for change data capture
- **Replica Identity**: Set appropriate `REPLICA IDENTITY` on tables for subscription support
- **Publication/Subscription Setup**: Configure logical replication publications and subscriptions
- **Connection Limits**: Ensure adequate `max_replication_slots` and `max_wal_senders` settings

These database administration features are handled by database administrators independently of this GraphQL API project.

## Quick Overview

Excalibase GraphQL automatically generates a complete GraphQL API from your PostgreSQL database schema. Simply point it at your database and get:

- ✅ **Auto-generated GraphQL types** from your tables
- ✅ **CRUD operations** for all your data
- ✅ **Advanced filtering** with 15+ operators
- ✅ **Relationship resolution** via foreign keys
- ✅ **Pagination** with cursor-based connections
- ✅ **Performance optimization** with N+1 query prevention

## 🔗 Important: Relationship Queries

When querying relationships, **always include the foreign key field(s)** in your selection:

```graphql
{
  orders {
    order_id
    customer_id        # ← Required for relationship
    customer {
      first_name
      last_name
    }
  }
}
```

**Why?** The relationship resolver needs the foreign key values to fetch related data. Without them, relationships will return `null`.

## Next Steps

1. **[API Reference →](../api/index.md)** - Explore the GraphQL API capabilities
2. **[Advanced Filtering →](../filtering.md)** - Learn about powerful filtering options
3. **[Testing →](../testing.md)** - Understand the comprehensive test coverage

## Need Help?

- 📚 Check the [API Reference](../api/index.md) for detailed documentation
- 🔧 Learn about [Advanced Filtering](../filtering.md) for complex queries
- 🐛 Report issues on [GitHub](https://github.com/excalibase/excalibase-graphql/issues)
- 📖 Read the [Contributing Guide](../CONTRIBUTING.md) to get involved

!!! tip "Docker Recommended"
    We recommend using Docker for the quickest setup experience. It eliminates dependency management and provides a consistent environment across different systems. 