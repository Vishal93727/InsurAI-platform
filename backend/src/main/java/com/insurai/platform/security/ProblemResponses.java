package com.insurai.platform.security;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/**
 * RFC 9457 problem bodies for security failures, which happen in filters before MVC's exception handling runs.
 * The shape matches what the core module's own error handler produces, so API clients see one error format.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, ObjectMapper json, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:insurai:problem:" + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
