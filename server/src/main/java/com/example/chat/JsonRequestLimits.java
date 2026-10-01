package com.example.chat;

import com.fasterxml.jackson.core.StreamReadConstraints;
import java.util.Map;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class JsonRequestLimits {
    @Bean Jackson2ObjectMapperBuilderCustomizer constrainJson() {
        return builder->builder.postConfigurer(mapper->mapper.getFactory().setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNumberLength(32).build()));
    }
    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<Map<String,String>> status(ResponseStatusException error) {
            return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason()==null?"请求未能完成":error.getReason()));
        }
        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<Map<String,String>> unreadable(HttpMessageNotReadableException error) {
            for(Throwable cause=error;cause!=null;cause=cause.getCause())
                if(cause instanceof ApiBodyLimit.TooLarge)return ResponseEntity.status(413).body(Map.of("error","请求内容超出大小限制"));
            return ResponseEntity.badRequest().body(Map.of("error","请求格式或字段大小无效"));
        }
    }
}
