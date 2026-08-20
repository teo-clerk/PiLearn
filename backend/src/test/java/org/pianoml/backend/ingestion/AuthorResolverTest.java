package org.pianoml.backend.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.pianoml.backend.entity.Author;
import org.pianoml.backend.repository.AuthorRepository;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Composer → {@link Author}.
 *
 * <p>{@code score.author_id} is NOT NULL, so every path through this class has to yield
 * an author. Returning null anywhere means an upload fails at the database with a
 * constraint violation — which is exactly how the missing-author bug reached a live
 * server the first time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthorResolverTest {

  @Mock private AuthorRepository authorRepository;

  private AuthorResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new AuthorResolver(authorRepository);
    when(authorRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    when(authorRepository.save(any(Author.class))).thenAnswer(invocation -> {
      Author saved = invocation.getArgument(0);
      saved.setId(UUID.randomUUID());
      return saved;
    });
  }

  @Test
  @DisplayName("an existing composer is reused, not duplicated")
  void existingAuthorIsReused() {
    Author chopin = new Author();
    chopin.setName("Chopin");
    when(authorRepository.findByNameIgnoreCase("Chopin")).thenReturn(Optional.of(chopin));

    assertThat(resolver.resolve("Chopin")).isSameAs(chopin);
    verify(authorRepository, never()).save(any());
  }

  @Test
  @DisplayName("a new composer is created")
  void newAuthorIsCreated() {
    Author created = resolver.resolve("Erik Satie");

    assertThat(created.getName()).isEqualTo("Erik Satie");
    assertThat(created.getSortName()).isEqualTo("Erik Satie");
  }

  @Test
  @DisplayName("no composer still yields an author — never null")
  void blankComposerResolvesToUnknown() {
    for (String blank : new String[] {null, "", "   "}) {
      Author author = resolver.resolve(blank);
      assertThat(author).isNotNull();
      assertThat(author.getName()).isEqualTo(AuthorResolver.UNKNOWN);
    }
  }

  @Test
  @DisplayName("the literal \"unknown\" the client sends does not create a second row")
  void clientSuppliedUnknownIsCanonical() {
    assertThat(resolver.resolve("unknown").getName()).isEqualTo(AuthorResolver.UNKNOWN);
    assertThat(resolver.resolve("UNKNOWN").getName()).isEqualTo(AuthorResolver.UNKNOWN);
  }

  @Test
  @DisplayName("surrounding whitespace does not create a distinct composer")
  void nameIsTrimmed() {
    assertThat(resolver.resolve("  Debussy  ").getName()).isEqualTo("Debussy");
  }

  @Test
  @DisplayName("losing an insert race reads the winner's row instead of failing the upload")
  void concurrentCreateFallsBackToTheWinner() {
    Author winner = new Author();
    winner.setName("Ravel");

    when(authorRepository.save(any(Author.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key value: author.name"));
    when(authorRepository.findByNameIgnoreCase("Ravel"))
        .thenReturn(Optional.empty())      // the lookup that made us try to insert
        .thenReturn(Optional.of(winner));  // the re-read after losing the race

    assertThat(resolver.resolve("Ravel")).isSameAs(winner);
  }
}
