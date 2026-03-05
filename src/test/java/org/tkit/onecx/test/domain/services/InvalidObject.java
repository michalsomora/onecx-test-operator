package org.tkit.onecx.test.domain.services;

import com.fasterxml.jackson.annotation.JsonProperty;

public class InvalidObject {

    @JsonProperty("s")
    private String x = "value1";

    @JsonProperty("s")
    private String y = "value2";
}
