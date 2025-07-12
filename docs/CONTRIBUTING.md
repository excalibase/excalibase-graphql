# Contributing to Excalibase GraphQL

Thank you for your interest in contributing! This project is currently in early development, and contributions are welcome.

## Getting Started

### Prerequisites
- Java 21+
- Maven 3.8+
- PostgreSQL 15+ (for testing)
- Git

### Development Setup

#### Option 1: Docker Development (Recommended)

1. **Fork and clone:**
   ```bash
   git clone https://github.com/your-username/excalibase-graphql.git
   cd excalibase-graphql
   ```

2. **Run with Docker Compose:**
   ```bash
   docker-compose up -d
   ```

3. **Run tests:**
   ```bash
   docker-compose exec app mvn test
   ```

#### Option 2: Local Development

1. **Fork and clone:**
   ```bash
   git clone https://github.com/your-username/excalibase-graphql.git
   cd excalibase-graphql
   ```

2. **Build and test:**
   ```bash
   mvn clean compile
   mvn test
   ```

3. **Run the application:**
   ```bash
   mvn spring-boot:run -Dspring.profiles.active=development
   ```

## Code Style and Standards

### Java Guidelines

- **Java Version**: Use Java 21+ features
- **Code Style**: Follow standard Java conventions
- **Line Length**: Maximum 120 characters
- **Indentation**: 4 spaces (no tabs)

### Important: No Lombok

**We avoid using Lombok in this project.** Please write standard Java getters, setters, and constructors manually.

❌ **Don't use:**
```java
@Data
@Builder
@AllArgsConstructor
public class TableInfo {
    private String name;
    private List<ColumnInfo> columns;
}
```

✅ **Instead write:**
```java
public class TableInfo {
    private String name;
    private List<ColumnInfo> columns;
    
    public TableInfo() {
    }
    
    public TableInfo(String name, List<ColumnInfo> columns) {
        this.name = name;
        this.columns = columns;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public List<ColumnInfo> getColumns() {
        return columns;
    }
    
    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns;
    }
}
```

### Documentation

- Add JavaDoc for public methods and classes
- Include inline comments for complex logic
- Keep comments up-to-date with code changes

```java
/**
 * Reflects database schema and returns table metadata.
 * 
 * @return map of table names to table information
 * @throws RuntimeException if database connection fails
 */
public Map<String, TableInfo> reflectSchema() {
    // Implementation
}
```

## Testing

### Test Structure

We use **Spock Framework** (Groovy) for testing with **Testcontainers** for database integration:

```groovy
class DatabaseReflectorTest extends Specification {
    
    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
    
    def setupSpec() {
        postgres.start()
    }
    
    def "should reflect table schema correctly"() {
        given: "a database with test table"
        // setup code
        
        when: "reflecting schema"
        def result = reflector.reflectSchema()
        
        then: "should return expected table info"
        result.size() == 1
        result.containsKey("test_table")
    }
}
```

### Testing Requirements

- **Add tests** for new functionality
- **All tests must pass**: `mvn test`
- **Use descriptive test names**: Describe what the test does
- **Use real databases**: Leverage Testcontainers for integration tests

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=PostgresDatabaseDataFetcherImplementTest

# Run with coverage
mvn clean test jacoco:report
```

## Commit Guidelines

### Commit Message Format

Use conventional commits format:

```
<type>(scope): <description>

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `test`: Adding or updating tests
- `refactor`: Code refactoring
- `chore`: Build/maintenance tasks

**Examples:**
```
feat(postgres): add support for UUID columns
fix(mutation): handle null foreign key values correctly
docs: update README with filtering examples
test: add integration tests for relationship queries
```

## Pull Request Process

1. **Create a feature branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes:**
    - Follow coding standards (no Lombok!)
    - Add tests for new functionality
    - Update documentation if needed

3. **Test your changes:**
   ```bash
   mvn clean test
   ```

4. **Commit and push:**
   ```bash
   git add .
   git commit -m "feat(scope): description of changes"
   git push origin feature/your-feature-name
   ```

5. **Create Pull Request:**
    - Use a descriptive title
    - Explain what your changes do
    - Reference any related issues

### Pull Request Checklist

- [ ] Code follows project standards (no Lombok)
- [ ] Tests added for new functionality
- [ ] All tests pass locally
- [ ] Documentation updated if needed
- [ ] Commit messages follow convention
- [ ] No unnecessary changes included

## Areas for Contribution

### Current Priorities

1. **MySQL Support**: Add MySQL database implementation
2. **Basic Authentication**: Simple JWT or API key auth
3. **Error Handling**: Better error messages and validation
4. **Documentation**: More examples and use cases
5. **Schema Caching**: Performance optimization for large schemas

### Good First Issues

- Adding new filter operators (e.g., `in`, `notIn`)
- Improving error messages
- Adding more unit tests
- Documentation improvements
- Code cleanup and refactoring

## Project Architecture

Understanding the codebase:

```
src/main/java/io/github/excalibase/
├── schema/reflector/     # Database introspection
├── schema/generator/     # GraphQL schema creation
├── schema/fetcher/       # Query resolvers
├── schema/mutator/       # Mutation resolvers
├── service/             # Service lookup and utilities
├── config/              # Spring configuration
├── model/               # Data models (no Lombok!)
└── constant/            # Constants and enums
```

### Adding Database Support

To add a new database (e.g., MySQL):

1. **Create reflector implementation:**
   ```java
   @ExcalibaseService(serviceName = "MySQL")
   public class MySQLDatabaseSchemaReflectorImplement implements IDatabaseSchemaReflector {
       // Implementation
   }
   ```

2. **Add corresponding generator, fetcher, and mutator classes**

3. **Add to DatabaseType enum**

4. **Write comprehensive tests**

## Questions and Help

### Getting Help

- **Check existing issues** first
- **Create a GitHub issue** for bugs or feature requests
- **Start a discussion** for questions or ideas

### Contact

This is currently a solo project, but I'm happy to help contributors:
- GitHub Issues: Bug reports and features
- GitHub Discussions: Questions and general discussion

## Code of Conduct

Please be respectful and constructive in all interactions. This project aims to be welcoming to contributors of all backgrounds and experience levels.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

---

Thank you for contributing to Excalibase GraphQL! 🚀