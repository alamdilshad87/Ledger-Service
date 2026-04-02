package dev.ledger.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledger.common.GlobalExceptionHandler.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

/** Writes the standard error body from a servlet filter, where the MVC exception handler doesn't reach. */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static void write(HttpServletResponse response, ObjectMapper json, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), new ErrorResponse(code, message, RequestIds.current()));
    }
}
