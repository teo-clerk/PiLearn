package org.pianoml.backend.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Object storage for score artefacts, using the layout in DATA_PIPELINE.md §3.
 *
 * <pre>
 *   raw/{scoreId}/original.{ext}                    immutable, never overwritten
 *   derived/{scoreId}/{revision}/score.musicxml
 *   derived/{scoreId}/{revision}/score.mid
 *   derived/{scoreId}/{revision}/document.json
 * </pre>
 *
 * <p>Raw and derived are separate prefixes on purpose. The legacy pipeline wrote its
 * result back to the same key it read the upload from, so ingesting a PDF destroyed the
 * PDF — the one artefact that cannot be regenerated. {@link #putRaw} refuses to
 * overwrite, so that cannot regress.
 *
 * <p>This is deliberately not {@code ScoreService.makeBucketKeyFromScore}, which derives
 * its key from owner + mbid + version. A freshly uploaded file has no mbid and no
 * meaningful version yet, and the score id alone is a complete identifier.
 */
@Service
@Slf4j
public class ScoreStorageService {

  private final S3Client s3Client;
  private final String bucketName;

  @Autowired
  public ScoreStorageService(
      S3Client s3Client, @Value("${aws.s3.bucket-name:pilearn-media}") String bucketName) {
    this.s3Client = s3Client;
    this.bucketName = bucketName;
  }

  public static String rawKey(UUID scoreId, String extension) {
    return "raw/" + scoreId + "/original" + normaliseExtension(extension);
  }

  public static String derivedKey(UUID scoreId, int revision, String name) {
    return "derived/" + scoreId + "/" + revision + "/" + name;
  }

  private static String normaliseExtension(String extension) {
    if (extension == null || extension.isBlank()) {
      return "";
    }
    return extension.startsWith(".") ? extension : "." + extension;
  }

  /**
   * Store an uploaded file.
   *
   * @throws ScoreStorageException if an object already exists at that key — the original
   *     upload is immutable, and a re-ingestion writes a new derived revision instead.
   */
  public String putRaw(UUID scoreId, String extension, byte[] content, String contentType) {
    String key = rawKey(scoreId, extension);

    if (exists(key)) {
      throw new ScoreStorageException(
          "refusing to overwrite the immutable original at " + key
              + "; re-ingestion writes a new derived revision, never a new raw object");
    }

    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(bucketName)
              .key(key)
              .contentType(contentType == null ? "application/octet-stream" : contentType)
              .build(),
          RequestBody.fromBytes(content));
    } catch (S3Exception e) {
      throw new ScoreStorageException("could not store the upload at " + key, e);
    }

    log.info("stored raw upload for score {} ({} bytes) at {}", scoreId, content.length, key);
    return key;
  }

  /** Read a derived artefact. Empty when it has not been produced yet. */
  public Optional<byte[]> getDerived(UUID scoreId, int revision, String name) {
    String key = derivedKey(scoreId, revision, name);
    try {
      return Optional.of(
          s3Client
              .getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build())
              .readAllBytes());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      // A missing object is an ordinary outcome; anything else is worth surfacing.
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw new ScoreStorageException("could not read " + key, e);
    } catch (IOException e) {
      throw new ScoreStorageException("could not read " + key, e);
    }
  }

  public boolean exists(String key) {
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (S3Exception e) {
      return false;
    }
  }
}
