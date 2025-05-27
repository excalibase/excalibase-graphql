package io.github.excalibase.controller;

import io.github.excalibase.exception.NotImplementedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/graphql")
public class GraphqlController {
    @PostMapping()
    public ResponseEntity<Map<String, Object>> graphql(@RequestBody Map<String, Object> request) {
        throw new NotImplementedException("graphql endpoint not implemented yet");
    }
}
