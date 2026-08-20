package org.pianoml.backend.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.entity.Author;
import org.pianoml.backend.repository.AuthorRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Finds or creates the {@link Author} a newly uploaded score belongs to.
 *
 * <p>{@code score.author_id} is NOT NULL, so an upload without a resolvable composer
 * cannot be stored at all. A learner scanning a photocopy usually does not know or care
 * who the publisher credits, and refusing the upload over it would be absurd — so an
 * unnamed composer resolves to a shared "Unknown" author rather than an error. The score
 * is still fully practisable, and the name can be corrected later.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthorResolver {

  static final String UNKNOWN = "Unknown";

  private final AuthorRepository authorRepository;

  public Author resolve(String composer) {
    String name = normalise(composer);

    return authorRepository
        .findByNameIgnoreCase(name)
        .orElseGet(() -> create(name));
  }

  private Author create(String name) {
    Author author = new Author();
    author.setName(name);
    author.setSortName(name);

    try {
      return authorRepository.save(author);
    } catch (DataIntegrityViolationException e) {
      // author.name is unique. Two uploads naming the same new composer at the same
      // moment both miss the lookup and both insert; the loser reads the winner's row
      // rather than failing an upload over a race.
      return authorRepository
          .findByNameIgnoreCase(name)
          .orElseThrow(() -> e);
    }
  }

  private String normalise(String composer) {
    if (composer == null || composer.isBlank()) {
      return UNKNOWN;
    }
    String trimmed = composer.trim();
    // The frontend sends the literal "Unknown" when the learner leaves it blank; do not
    // create a second author row that differs only in case.
    return trimmed.equalsIgnoreCase(UNKNOWN) ? UNKNOWN : trimmed;
  }
}
