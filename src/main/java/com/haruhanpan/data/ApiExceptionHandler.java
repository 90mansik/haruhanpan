package com.haruhanpan.data;

import com.haruhanpan.common.ApiErrors;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 하루한판 API 오류를 짧은 JSON 으로. 스택트레이스·SQL 은 응답에 넣지 않고 서버 로그에만 남긴다.
 * (basePackages 로 우리 컨트롤러에만 적용 — 정적 파일 404 등은 Spring 기본 처리)
 */
@RestControllerAdvice(basePackages = "com.haruhanpan")
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "이미지는 10MB 까지"));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
		return error(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, Object>> unreadable() {
		return error(HttpStatus.BAD_REQUEST, "잘못된 요청 본문");
	}

	@ExceptionHandler(ApiErrors.Forbidden.class)
	ResponseEntity<Map<String, Object>> forbidden(ApiErrors.Forbidden e) {
		return error(HttpStatus.FORBIDDEN, e.getMessage());
	}

	@ExceptionHandler(ApiErrors.NotFound.class)
	ResponseEntity<Map<String, Object>> notFound(ApiErrors.NotFound e) {
		return error(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(ApiErrors.Conflict.class)
	ResponseEntity<Map<String, Object>> conflict(ApiErrors.Conflict e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(Map.of("error", e.getMessage(), "version", e.currentVersion()));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
		log.warn("데이터 제약 위반: {}", e.getMostSpecificCause().getMessage());
		return error(HttpStatus.BAD_REQUEST, "저장할 수 없는 값 (길이·형식·중복 확인)");
	}

	/** Always Free ADB 가 7일 미사용으로 정지된 경우 등 */
	@ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
	ResponseEntity<Map<String, Object>> dbUnavailable(Exception e) {
		log.error("DB 연결 실패: {}", e.getMessage());
		return error(HttpStatus.SERVICE_UNAVAILABLE, "db unavailable");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> unexpected(Exception e) {
		log.error("처리 중 오류", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "server error");
	}

	private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message == null ? "" : message));
	}
}
