package dev.mccue.resolve.cli;

import dev.mccue.json.Json;
import dev.mccue.json.JsonDecoder;

record Repository(String url) {
    static Repository fromJson(Json json) {
        return new Repository(
                JsonDecoder.field(json, "url", JsonDecoder::string)
        );
    }
}
