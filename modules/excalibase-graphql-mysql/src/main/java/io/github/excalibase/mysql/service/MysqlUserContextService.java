package io.github.excalibase.mysql.service;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.service.IUserContextService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * MySQL no-op implementation of IUserContextService.
 * MySQL does not support Row Level Security, so user context is not applicable.
 */
@Service
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlUserContextService implements IUserContextService {

    @Override
    public void setUserContext(String userId, Map<String, String> additionalClaims) {
        // MySQL has no RLS — no-op
    }

    @Override
    public void clearUserContext() {
        // MySQL has no RLS — no-op
    }
}
