package incidents.service;

import static incidents.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import incidents.model.AddCommentCommand;
import incidents.model.CommentView;
import incidents.repository.IdempotencyKeyRepository;
import incidents.repository.IdempotencyKeyRepository.StoredKey;
import incidents.web.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    @Mock
    IdempotencyKeyRepository keys;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private IdempotencyServiceImpl idempotency;

    private final AddCommentCommand request = new AddCommentCommand("Also on mobile");
    private final CommentView comment = new CommentView(UUID.randomUUID(), UUID.randomUUID(), BOB.id(),
            "Also on mobile", NOW);
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        idempotency = new IdempotencyServiceImpl(keys, json, CLOCK);
    }

    private CommentView execute(String key, Object body) {
        return idempotency.execute(key, BOB.id(), "POST /incidents/1/comments", body, CommentView.class, () -> {
            runs.incrementAndGet();
            return comment;
        });
    }

    @Test
    void withoutAKeyTheActionJustRuns() {
        assertThat(execute(null, request)).isEqualTo(comment);

        assertThat(runs).hasValue(1);
        verify(keys, never()).claim(any(), any(), any(), any());
    }

    @Test
    void firstRequestRunsAndStoresItsResponse() throws Exception {
        when(keys.claim(eq("key-1"), eq(BOB.id()), anyString(), eq(NOW))).thenReturn(true);

        assertThat(execute("key-1", request)).isEqualTo(comment);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(keys).storeResponse(eq("key-1"), eq(BOB.id()), stored.capture());
        assertThat(json.readValue(stored.getValue(), CommentView.class)).isEqualTo(comment);
    }

    @Test
    void repeatReturnsTheStoredResponseWithoutRunning() throws Exception {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        when(keys.claim(eq("key-1"), eq(BOB.id()), hash.capture(), eq(NOW))).thenReturn(true, false);
        execute("key-1", request);
        when(keys.find("key-1", BOB.id()))
                .thenReturn(Optional.of(new StoredKey(hash.getValue(), json.writeValueAsString(comment))));

        assertThat(execute("key-1", new AddCommentCommand("Also on mobile"))).isEqualTo(comment);

        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyForADifferentRequestIs409() {
        when(keys.claim(eq("key-1"), eq(BOB.id()), anyString(), eq(NOW))).thenReturn(false);
        when(keys.find("key-1", BOB.id())).thenReturn(Optional.of(new StoredKey("other-hash", "{}")));

        assertThatThrownBy(() -> execute("key-1", request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different request");
        assertThat(runs).hasValue(0);
    }

    @Test
    void blankOrLongKeyIs400() {
        assertThatThrownBy(() -> execute(" ", request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> execute("k".repeat(101), request)).isInstanceOf(IllegalArgumentException.class);
    }
}
