package RAG.OLLAMA.demo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;

public class JettisonJsonObjectSerializer extends JsonSerializer<JSONObject> {

    @Override
    public void serialize(JSONObject value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        gen.writeStartObject();

        // Convert JSONObject to regular Jackson JSON
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object obj = value.get(key);
                gen.writeFieldName(key);

                // Handle different types
                if (obj == null || obj == JSONObject.NULL) {
                    gen.writeNull();
                } else if (obj instanceof String) {
                    gen.writeString((String) obj);
                } else if (obj instanceof Integer) {
                    gen.writeNumber((Integer) obj);
                } else if (obj instanceof Long) {
                    gen.writeNumber((Long) obj);
                } else if (obj instanceof Double) {
                    gen.writeNumber((Double) obj);
                } else if (obj instanceof Boolean) {
                    gen.writeBoolean((Boolean) obj);
                } else if (obj instanceof JSONObject) {
                    serialize((JSONObject) obj, gen, serializers);
                } else {
                    // Fallback for other types - convert to string
                    gen.writeString(obj.toString());
                }
            } catch (JSONException e) {
                throw new IOException("Error serializing JSONObject", e);
            }
        }

        gen.writeEndObject();
    }
}