package dev.mccue.resolve.cli;

import dev.mccue.json.Json;
import dev.mccue.json.JsonDecoder;

record Authentication(
        String username,
        String password
) {
    static Authentication fromJson(Json json) {
        return new Authentication(
                JsonDecoder.field(json, "username", JsonDecoder::string),
                JsonDecoder.field(json, "password", JsonDecoder::string)
        );
    }
}
