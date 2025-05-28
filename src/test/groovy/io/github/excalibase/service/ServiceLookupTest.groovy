package io.github.excalibase.service

import io.github.excalibase.annotation.ExcalibaseService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import spock.lang.Specification
import spock.lang.Unroll

@SpringBootTest(classes = [Excalibase1Service.class, Excalibase2Service.class, ServiceLookup.class])
class ServiceLookupTest extends Specification {
    @Autowired(required = false)
    private ServiceLookup serviceLookup

    @Unroll("test lookup #serviceType")
    def "test lookup for bean"() {
        expect:
        serviceLookup

        when:
        def someService = serviceLookup.forBean(SampleInterface.class, serviceType)
        def result = someService.dummyFunction()

        then:
        result == expectedResult

        where:
        serviceType   || expectedResult
        "excalibase1" || "Excalibase1"
        "excalibase2" || "Excalibase2"
    }
}

interface SampleInterface {
    String dummyFunction()
}

@ExcalibaseService(serviceName = "excalibase1")
class Excalibase1Service implements SampleInterface {
    @Override
    String dummyFunction() {
        return "Excalibase1"
    }
}

@ExcalibaseService(serviceName = "excalibase2")
class Excalibase2Service implements SampleInterface {
    @Override
    String dummyFunction() {
        return "Excalibase2"
    }
}
