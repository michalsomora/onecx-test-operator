package org.tkit.onecx.test.domain.services;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class TestServiceToJsonTest {

    @Inject
    TestService testService;

    @Inject
    ObjectMapper realMapper;

    @Test
    void toJson_serializesObjectToJson() throws JsonProcessingException {
        var input = new TestObject("hello", 42);

        String result = testService.toJson(input);

        assertThat(result).isEqualTo(realMapper.writeValueAsString(input));
    }

    @Test
    void toJson_serializesNullToJson() {
        String result = testService.toJson(null);

        assertThat(result).isEqualTo("null");
    }

    @Test
    void toJson_fallsBackToToStringOnJsonProcessingException() {
        // InvalidObject has two fields mapped to the same @JsonProperty("s"),
        // which causes Jackson to throw a JsonProcessingException naturally
        var input = new InvalidObject();

        String result = testService.toJson(input);

        assertThat(result).isEqualTo(String.valueOf(input));
    }

    static class TestObject {
        public final String name;
        public final int value;

        TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String toString() {
            return "TestObject{name='" + name + "', value=" + value + "}";
        }
    }
}
