package io.github.excalibase.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A multi-tenant engine routes realtime changes by project, which needs the project in the NATS subject
 * ({@code app.nats.tenant-in-subject=true}). With it off every project's sockets would share one stream of
 * changes, so the engine refuses to start that way. {@code app.nats.allow-untenanted-realtime=true} is the
 * explicit opt-out for a local test stack that serves no real tenants.
 */
@Component
public class TenantInSubjectGuard {

    private final boolean multiTenant;
    private final boolean natsEnabled;
    private final boolean tenantInSubject;
    private final boolean allowUntenanted;

    public TenantInSubjectGuard(@Value("${app.security.jwt-enabled:true}") boolean jwtEnabled,
                                @Value("${app.security.multi-tenant.provisioning-url:}") String provisioningUrl,
                                @Value("${app.nats.enabled:false}") boolean natsEnabled,
                                @Value("${app.nats.tenant-in-subject:false}") boolean tenantInSubject,
                                @Value("${app.nats.allow-untenanted-realtime:false}") boolean allowUntenanted) {
        this.multiTenant = jwtEnabled && provisioningUrl != null && !provisioningUrl.isBlank();
        this.natsEnabled = natsEnabled;
        this.tenantInSubject = tenantInSubject;
        this.allowUntenanted = allowUntenanted;
    }

    @PostConstruct
    void check() {
        if (multiTenant && natsEnabled && !tenantInSubject && !allowUntenanted) {
            throw new IllegalStateException("A multi-tenant engine (app.security.multi-tenant.provisioning-url) "
                    + "with NATS realtime needs app.nats.tenant-in-subject=true, or every project's WebSocket would "
                    + "receive the same changes. For a local test stack with no real tenants, "
                    + "set app.nats.allow-untenanted-realtime=true instead.");
        }
    }
}
