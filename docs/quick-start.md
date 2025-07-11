# Quick Start Guide - Excalibase GraphQL

This guide helps you quickly get started with Excalibase GraphQL using Docker Compose.

## Prerequisites

- Docker and Docker Compose installed on your system
- The `excalibase/excalibase-graphql` Docker image available locally or in a registry

## Quick Start

1. **Clone the repository and navigate to the project directory:**
   ```bash
   git clone <repository-url>
   cd excalibase-graphql
   ```

2. **Start the services:**
   ```bash
   docker-compose up -d
   ```

3. **Check the status:**
   ```bash
   docker-compose ps
   ```

4. **Access the application:**
   - GraphQL API: http://localhost:10000
   - Database: localhost:5432 (if you need direct access)

## What's Included

The Docker Compose setup provides a complete environment with:

### PostgreSQL Database
- **Image:** postgres:15-alpine
- **Container:** excalibase-postgres
- **Port:** 5432
- **Database:** hana
- **User:** hana001
- **Password:** password123
- **Schema:** hana

The database is automatically initialized with sample data including:
- Users table with sample users
- Posts table with sample blog posts
- Comments table with sample comments
- Proper indexes and foreign key relationships

### Excalibase GraphQL Application
- **Image:** excalibase/excalibase-graphql
- **Container:** excalibase-graphql
- **Port:** 10000
- **Health Check:** Available at `/actuator/health`

## Common Commands

### Start services
```bash
docker-compose up -d
```

### Stop services
```bash
docker-compose down
```

### View logs
```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f excalibase-app
docker-compose logs -f postgres
```

### Restart a service
```bash
docker-compose restart excalibase-app
```

### Connect to PostgreSQL
```bash
docker-compose exec postgres psql -U hana001 -d hana
```

### Reset everything (including data)
```bash
docker-compose down -v
docker-compose up -d
```

## Sample GraphQL Queries

Once the application is running at http://localhost:10000, you can try these sample queries:

### Get all users
```graphql
query {
  users {
    id
    username
    email
    firstName
    lastName
  }
}
```

### Get posts with authors
```graphql
query {
  posts {
    id
    title
    content
    published
    author {
      username
      email
    }
  }
}
```

### Get comments with post and author details
```graphql
query {
  comments {
    id
    content
    post {
      title
    }
    author {
      username
    }
  }
}
```

## Troubleshooting

### Application won't start
1. Check if the database is healthy:
   ```bash
   docker-compose ps
   ```

2. View application logs:
   ```bash
   docker-compose logs excalibase-app
   ```

### Database connection issues
1. Ensure the PostgreSQL service is running and healthy
2. Check if the database initialization completed successfully:
   ```bash
   docker-compose logs postgres
   ```

### Port conflicts
If you get port conflicts, you can modify the ports in `docker-compose.yml`:
```yaml
ports:
  - "YOUR_PORT:10000"  # Change YOUR_PORT to an available port
```

## Next Steps

- Explore the GraphQL schema at http://localhost:10000/graphql
- Check out the [API Documentation](index.md) for more details
- See [Contributing Guidelines](CONTRIBUTING.md) if you want to contribute

## Configuration

The application uses these default settings:
- Database: PostgreSQL with hana schema
- Application port: 10000
- Database port: 5432
- Default credentials: hana001/password123

To customize these settings, edit the environment variables in `docker-compose.yml`. 