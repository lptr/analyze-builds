package com.gradle.enterprise.export

import spock.lang.Specification

import static com.gradle.enterprise.export.Matcher.Match.EXCLUDE
import static com.gradle.enterprise.export.Matcher.Match.INCLUDE

class MatcherTest extends Specification {

    def "can match exact string"() {
        def matcher = matcher("alma")
        expect:
        matcher.matches("alma").get() == INCLUDE
        matcher.matches("korte").empty
        matcher.matches("").empty
    }

    def "can match excluded exact string"() {
        def matcher = matcher("!alma")
        expect:
        matcher.matches("alma").get() == EXCLUDE
        matcher.matches("korte").empty
        matcher.matches("").empty
    }

    def "can match regular expression"() {
        def matcher = matcher("/a.*a/")
        expect:
        matcher.matches("alma").get() == INCLUDE
        matcher.matches("korte").empty
        matcher.matches("").empty
    }

    def "can match excluded regular expression"() {
        def matcher = matcher("!/a.*a/")
        expect:
        matcher.matches("alma").get() == EXCLUDE
        matcher.matches("korte").empty
        matcher.matches("").empty
    }

    Matcher matcher(String pattern) {
        new Matcher.Converter().convert(pattern)
    }
}
