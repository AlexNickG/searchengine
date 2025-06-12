package searchengine.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import searchengine.dto.statistics.ResponseMessage;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler
    ResponseEntity<ResponseMessage> catchResourceDoesNotMatchException(ResourceDoesNotMatchException e) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(false);
        responseMessage.setError(e.getMessage());
        return new ResponseEntity<>(responseMessage, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler
    ResponseEntity<ResponseMessage> catchBadRequestException(BadRequestException e) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(false);
        responseMessage.setError(e.getMessage());
        return new ResponseEntity<>(responseMessage, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler
    ResponseEntity<ResponseMessage> catchResourceForbiddenException(ResourceForbiddenException e) {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setResult(false);
        responseMessage.setError(e.getMessage());
        return new ResponseEntity<>(responseMessage, HttpStatus.FORBIDDEN);
    }
}
