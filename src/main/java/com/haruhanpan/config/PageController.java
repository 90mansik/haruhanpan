package com.haruhanpan.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 화면 주소. 로그인하면 홈(앱 고르기)에서 시작한다.
 * <pre>
 * /        홈: 하루한판 · 하루가계부 카드 (home.html)
 * /board   하루한판: 할 일·일정·루틴·메모 (index.html)
 * /ledger  하루가계부 (LedgerController)
 * </pre>
 */
@Controller
public class PageController {

	@GetMapping("/")
	public String home() {
		return "forward:/home.html";
	}

	@GetMapping("/board")
	public String board() {
		return "forward:/index.html";
	}
}
