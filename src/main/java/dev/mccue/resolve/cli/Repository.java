package dev.mccue.resolve.cli;

import dev.mccue.json.Json;
import dev.mccue.json.JsonDecoder;

import java.util.Optional;

record Repository(
        String url,
        Optional<Authentication> authentication
) {
    static Repository fromJson(Json json) {
        return new Repository(
                JsonDecoder.field(json, "url", JsonDecoder::string),
                JsonDecoder.optionalField(json, "authentication", Authentication::fromJson)
        );
    }
}
