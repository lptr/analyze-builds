package com.gradle.enterprise.export

import spock.lang.Specification

class MatcherTest extends Specification {

    def "can match exact string"() {
        def matcher = matcher("alma")
        expect:
        matcher.matches("alma")
        !matcher.matches("korte")
        !matcher.matches("")
    }

    def "can match excluded exact string"() {
        def matcher = matcher("!alma")
        expect:
        !matcher.matches("alma")
        matcher.matches("korte")
        matcher.matches("")
    }

    def "can match regular expression"() {
        def matcher = matcher("/a.*a/")
        expect:
        matcher.matches("alma")
        !matcher.matches("korte")
        !matcher.matches("")
    }

    def "can match excluded regular expression"() {
        def matcher = matcher("!/a.*a/")
        expect:
        !matcher.matches("alma")
        matcher.matches("korte")
        matcher.matches("")
    }

    Matcher matcher(String pattern) {
        new Matcher.Converter().convert(pattern)
    }
}
