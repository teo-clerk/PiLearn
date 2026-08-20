import { isPlatformBrowser } from '@angular/common';
import { Injectable, PLATFORM_ID, inject, signal } from '@angular/core';

const STORAGE_KEY = 'pilearn.guestSession';

/**
 * The identity of a visitor who has not signed in.
 *
 * A visitor must be able to upload a score and practise it before creating an account —
 * asking for a sign-up before they have seen a single bar render is the wrong order. The
 * backend attaches such uploads to a seeded guest owner and tags them with the id held
 * here, so this browser can find its own scores again and a later sign-up can claim them.
 *
 * The id is minted by the backend, not here: it is a database key, and letting the client
 * choose it would mean trusting client-supplied input as an identifier.
 */
@Injectable({ providedIn: 'root' })
export class GuestSessionService {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly isBrowser = isPlatformBrowser(this.platformId);

  /** Null until the first anonymous upload comes back with an id. */
  readonly sessionId = signal<string | null>(this.read());

  /** Remember an id the backend issued. Ignores anything that is not a plausible id. */
  remember(sessionId: string | null | undefined): void {
    if (!sessionId || !/^[A-Za-z0-9_-]{8,64}$/.test(sessionId)) {
      return;
    }
    this.sessionId.set(sessionId);
    this.write(sessionId);
  }

  private read(): string | null {
    if (!this.isBrowser) return null;
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      return stored && /^[A-Za-z0-9_-]{8,64}$/.test(stored) ? stored : null;
    } catch {
      return null;
    }
  }

  private write(sessionId: string): void {
    if (!this.isBrowser) return;
    try {
      localStorage.setItem(STORAGE_KEY, sessionId);
    } catch {
      // Private browsing or a full quota — not worth surfacing to the learner.
    }
  }
}
