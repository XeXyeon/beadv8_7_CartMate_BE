package com.cartmate.cartmate;

import com.cartmate.cartmate.support.ProductServiceTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 읽기 전용 트랜잭션이 SET SESSION TRANSACTION READ ONLY / READ WRITE를 보내지 않는지 본다
 * (application.yaml의 readOnlyPropagatesToServer: false).
 * MySQL이 센 SET 문장 수(Com_set_option)로 확인한다. 트랜잭션 하나에 남는 SET은 autocommit 끄기 · 켜기 2개뿐이어야 한다(옵션이 없으면 4개).
 */
class ReadOnlyTransactionSetOptionTest extends ProductServiceTestSupport {

    private static final int TRANSACTIONS = 20;

    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("읽기 전용 트랜잭션: 트랜잭션마다 SET은 autocommit 2개만 나가고, 읽기 전용 SET 2개는 나가지 않는다")
    void readOnlyTransactionSendsNoReadOnlySet() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        readOnly.execute(status -> jdbcTemplate.queryForObject("select 1", Integer.class));   // 커넥션 준비 등 첫 실행 비용 제외

        long before = setOptionCount();
        for (int i = 0; i < TRANSACTIONS; i++) {
            readOnly.execute(status -> jdbcTemplate.queryForObject("select 1", Integer.class));
        }
        long sent = setOptionCount() - before;

        // 옵션이 없으면 4 × 20 = 80개. 다른 스레드(스케줄러 등)가 끼어들 몫으로 10개 여유를 둔다
        assertThat(sent).isLessThanOrEqualTo(2L * TRANSACTIONS + 10);
    }

    // 트랜잭션 밖(autocommit)에서 읽어 SET을 보태지 않는다
    private long setOptionCount() {
        return jdbcTemplate.queryForObject("SHOW GLOBAL STATUS LIKE 'Com_set_option'",
                (rs, row) -> rs.getLong("Value"));
    }
}
