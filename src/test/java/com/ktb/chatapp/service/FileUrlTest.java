package com.ktb.chatapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FileUrl 단위 테스트")
class FileUrlTest {

    @Test
    @DisplayName("of()는 key 앞에 /api/files/ 접두사를 붙인다")
    void of_prependsFilesPrefix() {
        assertThat(FileUrl.of("profiles/avatar.png")).isEqualTo("/api/files/profiles/avatar.png");
    }

    @Test
    @DisplayName("of()가 만드는 URL은 프론트가 베이스 URL을 붙일 수 있는 상대경로다")
    void of_returnsRelativePath() {
        assertThat(FileUrl.of("profiles/avatar.png")).startsWith("/").doesNotStartWith("http");
    }

    @Test
    @DisplayName("of()는 값이 없으면 그대로 통과시킨다")
    void of_passesThroughEmptyValues() {
        assertThat(FileUrl.of(null)).isNull();
        assertThat(FileUrl.of("")).isEmpty();
    }

    @Test
    @DisplayName("publicMediaOf()는 CDN 공개 key만 상대경로로 변환한다")
    void publicMediaOf_returnsOnlyMediaPaths() {
        assertThat(FileUrl.publicMediaOf("media/chat/previews/photo-preview.jpg"))
                .isEqualTo("/media/chat/previews/photo-preview.jpg");
        assertThat(FileUrl.publicMediaOf("chat/photo.png")).isNull();
    }
}
