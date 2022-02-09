package com.gradle.enterprise.export

import spock.lang.Specification

class FilterTest extends Specification {
    def "null filter matches everything"() {
        def filter = new Filter(null)
        expect:
        !filter.filters()
        filter.matches("alma")
        filter.matches([])
    }

    def "empty filter matches everything"() {
        def filter = filter()
        expect:
        !filter.filters()
        filter.matches("alma")
        filter.matches([])
    }

    def "singe matcher filters"() {
        def filter = filter(
            { it.equals("alma") } as Matcher
        )
        expect:
        filter.filters()
        filter.matches("alma")
        !filter.matches("korte")
        filter.matches(["alma", "korte"])
        filter.matches(["korte", "alma"])
        !filter.matches([])
    }

    def "multi matcher filters"() {
        def filter = filter(
            { it.equals("alma") } as Matcher,
            { it.equals("korte") } as Matcher
        )
        expect:
        filter.filters()
        filter.matches("alma")
        filter.matches("korte")
        !filter.matches("dinnye")
        filter.matches(["alma", "dinnye"])
        filter.matches(["korte", "dinnye"])
        !filter.matches([])
    }

    Filter filter(Matcher... matchers) {
        return new Filter(matchers.toList())
    }
}
