package io.github.excalibase.service;

import io.github.excalibase.annotation.ExcalibaseService;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ServiceLookup {
    private final ApplicationContext applicationContext;

    public ServiceLookup(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public <T> T forBean(Class<T> serviceClass, String componentType) {

        Map<String, T> beans = applicationContext.getBeansOfType(serviceClass);

        return beans.entrySet().stream()
                .filter(entry -> getAnnotatedComponentType(entry.getKey()).equals(componentType))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new NoSuchBeanDefinitionException(serviceClass.getSimpleName(), componentType));

    }

    private String getAnnotatedComponentType(String beanName) {
        ExcalibaseService excalibaseService = applicationContext.findAnnotationOnBean(beanName, ExcalibaseService.class);
        return excalibaseService == null ? "" : excalibaseService.serviceName();
    }
}
