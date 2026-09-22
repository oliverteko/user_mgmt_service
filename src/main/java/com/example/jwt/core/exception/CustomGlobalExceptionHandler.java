package com.example.jwt.core.exception;

import com.example.jwt.domain.module.ModuleNotFoundException;
import com.example.jwt.domain.module.ModuleServiceUnavailableException;
import com.example.jwt.domain.module.UserNotFoundException;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class CustomGlobalExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(CustomGlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ResponseError handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
    return new ResponseError()
        .setTimeStamp(LocalDate.now())
        .setErrors(ex.getBindingResult().getFieldErrors().stream().collect(
            Collectors.toMap(error -> error.getField(), error -> error.getDefaultMessage())))
        .build();
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ResponseError handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return error(ex.getName(), String.format("'%s' is not a valid value", ex.getValue()));
  }

  @ExceptionHandler(UserNotFoundException.class)
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  public ResponseError handleUserNotFound(UserNotFoundException ex) {
    return error("user", ex.getMessage());
  }

  @ExceptionHandler(ModuleNotFoundException.class)
  @ResponseStatus(value = HttpStatus.NOT_FOUND)
  public ResponseError handleModuleNotFound(ModuleNotFoundException ex) {
    return error("module", ex.getMessage());
  }

  @ExceptionHandler(ModuleServiceUnavailableException.class)
  @ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
  public ResponseError handleModuleServiceUnavailable(ModuleServiceUnavailableException ex) {
    LOGGER.warn("module_service call failed: {}", ex.getMessage());
    return error("moduleService", "Module service is temporarily unavailable, try again later");
  }

  private static ResponseError error(String field, String message) {
    return new ResponseError()
        .setTimeStamp(LocalDate.now())
        .setErrors(Map.of(field, message))
        .build();
  }

}
