# Feature Flags

Excalibase GraphQL supports feature flags for flexible configuration and deployment strategies.

## Available Feature Flags

### Role-Based Security

**Property:** `app.security.role-based-schema`  
**Type:** `boolean`  
**Default:** `true`  

Controls whether role-based schema filtering and PostgreSQL RLS/CLS support is enabled.

```yaml
app:
  security:
    role-based-schema: true  # Enable role-based security (recommended)
```

**See:** [Role-Based Security Documentation](./role-based-security.md)

## Configuration Patterns

### Environment Variables

```yaml
app:
  security:
    role-based-schema: ${ROLE_BASED_SECURITY:true}
```

```bash
export ROLE_BASED_SECURITY=false  # Disable temporarily
export ROLE_BASED_SECURITY=true   # Enable
```

### Profile-Specific Configuration

```yaml
# application-dev.yaml
app:
  security:
    role-based-schema: false  # Simplified for development

# application-prod.yaml  
app:
  security:
    role-based-schema: true   # Full security in production
```

### Runtime Toggle (Future)

Feature flags may support runtime toggling in future versions through:
- Admin REST endpoints
- JMX beans
- Configuration refresh events

## Best Practices

1. **Use Environment Variables:** Allow runtime configuration without code changes
2. **Document Changes:** Always document feature flag changes in deployment notes
3. **Test Both States:** Ensure functionality works with flags both enabled and disabled
4. **Monitor Impact:** Watch performance and security metrics when toggling flags
5. **Plan Rollback:** Always have a rollback strategy for feature flag changes

## Adding New Feature Flags

When adding new feature flags:

1. **Add to AppConfig:** Define the property in `AppConfig.SecurityConfig` or create new config classes
2. **Implement Logic:** Add feature flag checks in relevant services
3. **Update Configuration:** Add to `application.yaml` with sensible defaults
4. **Document:** Create documentation explaining the feature flag behavior
5. **Test:** Add tests for both enabled and disabled states

Example pattern:
```java
// In service class
if (appConfig.getSecurity().isNewFeature()) {
    // New behavior
} else {
    // Legacy behavior  
}
```

## Current Limitations

- Feature flags currently require application restart to take effect
- No runtime monitoring dashboard (planned for future releases)
- Limited to boolean flags (no A/B testing percentages yet)

## Future Enhancements

- **Runtime Toggle:** Change flags without restart
- **Percentage Rollouts:** Gradual rollouts to percentage of users  
- **Monitoring Dashboard:** Real-time feature flag status and metrics
- **Conditional Logic:** Complex conditions based on user attributes
- **Audit Trail:** Track all feature flag changes with timestamps and users
- Or integrate with third-party feature flag providers (e.g., LaunchDarkly, Unleash, ConfigCat) for advanced flag management and dynamic toggling