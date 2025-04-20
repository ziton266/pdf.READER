package RAG.OLLAMA.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import org.codehaus.jettison.json.JSONObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();

        // Register Joda module for DateTime serialization
        objectMapper.registerModule(new JodaModule());

        // Disable failing on empty beans which helps with Jettison JSONObject
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        // Create custom module for Jettison JSONObject serialization
        SimpleModule jettisonModule = new SimpleModule();
        jettisonModule.addSerializer(JSONObject.class, new JettisonJsonObjectSerializer());
        objectMapper.registerModule(jettisonModule);

        return objectMapper;
    }
}