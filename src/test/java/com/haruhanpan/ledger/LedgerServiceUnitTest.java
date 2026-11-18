package com.haruhanpan.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** 가계부 계산 함수 (DB 없이) */
class LedgerServiceUnitTest {

	@Test
	void 결제일이_그_달에_없으면_말일() {
		assertThat(LedgerService.dueDate(YearMonth.of(2026, 2), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
		assertThat(LedgerService.dueDate(YearMonth.of(2028, 2), 30)).isEqualTo(LocalDate.of(2028, 2, 29));
		assertThat(LedgerService.dueDate(YearMonth.of(2026, 4), 31)).isEqualTo(LocalDate.of(2026, 4, 30));
		assertThat(LedgerService.dueDate(YearMonth.of(2026, 1), 17)).isEqualTo(LocalDate.of(2026, 1, 17));
	}

	@Test
	void 월_시작일에_따른_기간() {
		LocalDate[] p = LedgerService.period(YearMonth.of(2026, 3), 1);
		assertThat(p).containsExactly(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
		p = LedgerService.period(YearMonth.of(2026, 3), 25);
		assertThat(p).containsExactly(LocalDate.of(2026, 2, 25), LocalDate.of(2026, 3, 24));
		p = LedgerService.period(YearMonth.of(2026, 1), 10);
		assertThat(p).containsExactly(LocalDate.of(2025, 12, 10), LocalDate.of(2026, 1, 9));
	}
}
