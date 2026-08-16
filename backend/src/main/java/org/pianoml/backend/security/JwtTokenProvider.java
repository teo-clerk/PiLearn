package org.pianoml.backend.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.pianoml.backend.config.JwtProperties;
import org.pianoml.backend.entity.User;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Slf4j
public class JwtTokenProvider {

  private final Algorithm algorithm;
  private final JWTVerifier verifier;

  /** Token lifetime in milliseconds, from configuration. */
  @Getter
  private final long jwtExpirationInMs;

  public JwtTokenProvider(JwtProperties properties) {
    this.algorithm = Algorithm.HMAC512(properties.secret());
    this.verifier = JWT.require(this.algorithm).build();
    this.jwtExpirationInMs = properties.expirationMs();
  }

  public String generateToken(User user) {
    Date now = new Date();
    Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

    return JWT.create()
      .withSubject(user.getId().toString())
      .withIssuedAt(now)
      .withExpiresAt(expiryDate)
      .sign(algorithm);
  }

  public String getUserIdFromJWT(String token) {
    DecodedJWT jwt = verifier.verify(token);
    return jwt.getSubject();
  }

  public boolean validateToken(String authToken) {
    try {
      verifier.verify(authToken);
      return true;
    } catch (Exception ex) {
      // Expected for expired/tampered tokens. Logged at debug: a failed verification is a
      // routine event on any public endpoint and must never leak token contents.
      log.debug("JWT verification failed: {}", ex.getMessage());
      return false;
    }
  }
}
