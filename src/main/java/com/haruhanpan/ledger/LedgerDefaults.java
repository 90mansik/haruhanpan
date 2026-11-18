package com.haruhanpan.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공간에 가계부 설정이 아직 없을 때 쓰는 기본 분류·결제수단.
 * 참고한 엑셀 가계부의 분류에서 "남편월급 / 아내월급"처럼 사람이 들어간 이름은 빼고
 * (누구 것인지는 내역의 '사람' 칸으로 남긴다) 나머지는 그대로 옮겼다.
 */
final class LedgerDefaults {

	static final int MONTH_START_DAY = 1;

	static final List<String> METHODS = List.of("생활비카드", "가족카드", "지역상품권", "현금/이체", "복지포인트");

	private LedgerDefaults() {
	}

	/** kind(income·saving·expense) → 대분류 → 소분류 목록 (순서 유지) */
	static Map<String, Map<String, List<String>>> categories() {
		Map<String, Map<String, List<String>>> all = new LinkedHashMap<>();

		Map<String, List<String>> income = new LinkedHashMap<>();
		income.put("월급", List.of());
		income.put("상여", List.of());
		income.put("수당", List.of());
		income.put("기타수입", List.of("부수입", "투자소득", "기타소득"));
		all.put("income", income);

		Map<String, List<String>> saving = new LinkedHashMap<>();
		saving.put("저축·투자", List.of("청약통장", "달러통장", "달러/금", "주식/CMA"));
		saving.put("연금·노후", List.of("개인연금", "퇴직연금", "국민연금"));
		saving.put("목적통장", List.of("비상금통장", "여행통장", "효도통장"));
		all.put("saving", saving);

		Map<String, List<String>> expense = new LinkedHashMap<>();
		expense.put("주거", List.of("월세", "관리비", "도시가스", "수리/청소", "대출이자"));
		expense.put("식비", List.of("식자재", "외식", "카페류"));
		expense.put("생활용품", List.of("생활소모품", "가전/가구", "침구/인테리어"));
		expense.put("꾸밈비", List.of("의류/잡화", "미용/헤어", "세탁/수선"));
		expense.put("건강", List.of("병원/약국", "건강보조", "예방/검진"));
		expense.put("자기계발", List.of("스터디/수강", "운동", "구독/도서"));
		expense.put("자동차", List.of("주유", "주차/통행료", "대중교통", "택시", "소모품/수리", "보험/세금"));
		expense.put("자녀", List.of("육아용품", "놀이/활동", "교육/도서", "꾸밈/의류", "기관/돌봄"));
		expense.put("반려동물", List.of("반려용품", "동물병원"));
		expense.put("여행", List.of("국내여행", "해외여행"));
		expense.put("문화생활", List.of("공연/전시", "OTT/구독", "입장료"));
		expense.put("경조사", List.of("가족/친척", "지인/동료"));
		expense.put("용돈", List.of());
		expense.put("보험", List.of());
		expense.put("통신비", List.of("휴대폰", "인터넷/TV", "수리/Acc"));
		expense.put("기타", List.of("세금", "회비", "서비스"));
		all.put("expense", expense);
		return all;
	}
}
