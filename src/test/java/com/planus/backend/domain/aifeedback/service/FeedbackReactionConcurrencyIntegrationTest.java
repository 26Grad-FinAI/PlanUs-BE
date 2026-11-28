package com.planus.backend.domain.aifeedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.planus.backend.TestcontainersConfig;
import com.planus.backend.domain.aifeedback.dto.FeedbackReactionRequest;
import com.planus.backend.domain.aifeedback.entity.AiFeedback;
import com.planus.backend.domain.aifeedback.entity.Reaction;
import com.planus.backend.domain.aifeedback.entity.RejectionReason;
import com.planus.backend.domain.aifeedback.entity.UserReaction;
import com.planus.backend.domain.aifeedback.repository.AiFeedbackRepository;
import com.planus.backend.domain.aifeedback.repository.UserReactionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/** 같은 반응이 동시에 처음 들어와도 행이 하나만 남는지, 유일 제약이 NULL 카테고리까지 막는지 실제 PostgreSQL로 확인한다 (#160). */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class FeedbackReactionConcurrencyIntegrationTest {

    private Long user;

    @Autowired
    private com.planus.backend.domain.user.repository.UserAccountRepository userRepo;

    @Autowired
    private FeedbackReactionService service;

    @Autowired
    private UserReactionRepository reactionRepo;

    @Autowired
    private AiFeedbackRepository feedbackRepo;

    private Long feedbackId;

    @AfterEach
    void tearDown() {
        if (user != null) reactionRepo.deleteAll(reactionRepo.findByUserId(user));
        if (feedbackId != null) feedbackRepo.deleteById(feedbackId);
        if (user != null) userRepo.deleteById(user);
    }

    private void givenFeedback() {
        user = userRepo.save(com.planus.backend.domain.user.entity.UserAccount.builder()
                        .email("reaction-" + System.nanoTime() + "@test.com")
                        .password("pw")
                        .build())
                .getId();
        feedbackId = feedbackRepo
                .save(AiFeedback.builder()
                        .userId(user)
                        .yearMonth(LocalDate.of(2026, 11, 1))
                        .periodType("WEEKLY")
                        .periodStart(LocalDate.of(2026, 11, 9))
                        .periodEnd(LocalDate.of(2026, 11, 15))
                        .payload("{\"actions\":[{\"categoryId\":10}]}")
                        .build())
                .getId();
    }

    private void runConcurrently(int threads, Runnable task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    task.run();
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            ts.add(t);
            t.start();
        }
        ready.await();
        go.countDown();
        for (Thread t : ts) t.join(20_000);
        assertThat(errors).as("동시 요청 모두 오류 없이 끝나야 한다").isEmpty();
    }

    @Test
    @DisplayName("같은 거절 반응이 동시에 8번 처음 들어와도 행은 하나다 (카테고리 있음)")
    void concurrentFirstRejection_singleRow() throws Exception {
        givenFeedback();

        runConcurrently(
                8,
                () -> service.save(
                        user,
                        feedbackId,
                        new FeedbackReactionRequest(Reaction.NOT_HELPFUL, RejectionReason.DONT_WANT_REDUCE, 10)));

        assertThat(reactionRepo.findByUserIdAndFeedbackId(user, feedbackId)).hasSize(1);
    }

    @Test
    @DisplayName("피드백 전체에 대한 반응(카테고리 없음)도 동시에 들어와 행이 하나다 — NULL 카테고리까지 막는다")
    void concurrentFirstHelpful_nullCategory_singleRow() throws Exception {
        givenFeedback();

        runConcurrently(
                8, () -> service.save(user, feedbackId, new FeedbackReactionRequest(Reaction.HELPFUL, null, null)));

        assertThat(reactionRepo.findByUserIdAndFeedbackId(user, feedbackId)).hasSize(1);
    }

    @Test
    @DisplayName("DB가 직접 막는다 — 서비스를 거치지 않은 중복 INSERT도 카테고리 있음·없음 모두 거절된다")
    void databaseRejectsDuplicates() {
        givenFeedback();
        for (Integer category : new Integer[] {10, null}) {
            UserReaction a = UserReaction.builder()
                    .feedbackId(feedbackId)
                    .userId(user)
                    .reaction(Reaction.HELPFUL)
                    .categoryId(category)
                    .build();
            reactionRepo.saveAndFlush(a);
            UserReaction dup = UserReaction.builder()
                    .feedbackId(feedbackId)
                    .userId(user)
                    .reaction(Reaction.HELPFUL)
                    .categoryId(category)
                    .build();
            assertThatThrownBy(() -> reactionRepo.saveAndFlush(dup))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
