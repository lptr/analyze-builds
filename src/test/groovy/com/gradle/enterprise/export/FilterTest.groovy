package com.gradle.enterprise.export

import spock.lang.Specification

import java.util.function.Predicate

import static com.gradle.enterprise.export.Matcher.Match.EXCLUDE
import static com.gradle.enterprise.export.Matcher.Match.INCLUDE

class FilterTest extends Specification {
    def "null filter matches everything"() {
        def filter = Filter.create(null)
        expect:
        !filter.filters()
        filter.matches("alma")
    }

    def "empty filter matches everything"() {
        def filter = filter()
        expect:
        !filter.filters()
        filter.matches("alma")
    }

    def "singe matcher filters"() {
        def filter = filter(
            include { it.equals("alma") }
        )
        expect:
        filter.filters()
        filter.matches("alma")
        !filter.matches("korte")
        filter.matchesAny(["alma", "korte"])
        filter.matchesAny(["korte", "alma"])
    }

    def "multi matcher filters"() {
        def filter = filter(
            include { it.endsWith("alma") },
            include { it.endsWith("korte") },
            exclude { it.startsWith("zold") },
            exclude { it.startsWith("sarga") },
        )
        expect:
        filter.filters()
        filter.matches("alma")
        filter.matches("korte")
        !filter.matches("dinnye")
        filter.matches("pirosalma")
        !filter.matches("zoldalma")
        !filter.matches("sargaalma")
    }

    def "include-only filter matches"() {
        def filter = filter(
            include { it.endsWith("alma") },
            include { it.endsWith("korte") },
        )
        expect:
        filter.filters()
        filter.matches("alma")
        filter.matches("korte")
        !filter.matches("dinnye")
    }

    def "exclude-only filter matches"() {
        def filter = filter(
            exclude { it.startsWith("zold") },
            exclude { it.startsWith("sarga") },
        )
        expect:
        filter.filters()
        filter.matches("alma")
        filter.matches("korte")
        !filter.matches("zoldalma")
        !filter.matches("sargaalma")
    }

    Matcher include(Predicate<String> predicate) {
        matcher(predicate, INCLUDE)
    }

    Matcher exclude(Predicate<String> predicate) {
        matcher(predicate, EXCLUDE)
    }

    Matcher matcher(Predicate<String> predicate, Matcher.Match direction) {
        return new Matcher(direction) {
            @Override
            protected boolean match(String value) {
                return predicate.test(value)
            }

            @Override
            protected String describeValue() {
                return "test"
            }
        }
    }

    Filter filter(Matcher... matchers) {
        return Filter.create(matchers.toList())
    }
}
