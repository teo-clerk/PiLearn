import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { type Observable, catchError, of, tap } from 'rxjs';
import { GuestSessionService } from '../session/guest-session.service';
import { environment } from '../../../environments/environment';
import { DEFAULT_PROFILE, type UserProfile } from './user-profile.model';

/**
 * The learner's skill profile.
 *
 * Loads once per session and is then read synchronously as a signal, because the practice
 * surface consults it on every stage change and an async read there would make the note
 * labels flicker on.
 *
 * Failures are deliberately silent: a learner whose profile could not be fetched should
 * get the default roadmap, not an error page between them and the piano. `loaded` says
 * whether the answer is authoritative, which is what gates the questionnaire.
 */
@Injectable({ providedIn: 'root' })
export class UserProfileService {
  private readonly http = inject(HttpClient);
  private readonly guestSession = inject(GuestSessionService);
  private readonly baseUrl = environment.api;

  private readonly profileState = signal<UserProfile>(DEFAULT_PROFILE);
  private readonly loadedState = signal(false);
  private inFlight: Observable<UserProfile> | null = null;

  readonly profile = this.profileState.asReadonly();
  readonly loaded = this.loadedState.asReadonly();

  readonly skillLevel = computed(() => this.profileState().skillLevel);
  readonly preferredInput = computed(() => this.profileState().preferredInput);

  /**
   * Whether to ask the two onboarding questions.
   *
   * Only once the profile has actually loaded — asking while the answer is still in
   * flight would show the questionnaire to someone who completed it last week.
   */
  readonly needsOnboarding = computed(
    () => this.loadedState() && !this.profileState().onboarded,
  );

  /** Fetch the profile. Safe to call repeatedly; the request is shared. */
  load(): Observable<UserProfile> {
    if (this.inFlight) return this.inFlight;

    const sessionId = this.guestSession.sessionId();
    const url = sessionId
      ? `${this.baseUrl}/api/v1/profile?guestSessionId=${encodeURIComponent(sessionId)}`
      : `${this.baseUrl}/api/v1/profile`;

    this.inFlight = this.http.get<UserProfile>(url).pipe(
      tap((profile) => this.accept(profile)),
      catchError((error: unknown) => {
        // A learner with no backend still gets a usable surface. `loaded` stays false,
        // so the questionnaire is not shown on the strength of a failed request.
        this.inFlight = null;
        if (!(error instanceof HttpErrorResponse)) throw error;
        return of(this.profileState());
      }),
    );

    return this.inFlight;
  }

  /**
   * Save the questionnaire answers.
   *
   * Sends only what changed. A partial update must never restate fields it did not
   * measure, or switching input device would silently reset the skill level.
   */
  save(changes: Partial<UserProfile> & { onboarded?: boolean }): Observable<UserProfile> {
    const body = {
      ...changes,
      guestSessionId: this.guestSession.sessionId(),
    };

    return this.http.post<UserProfile>(`${this.baseUrl}/api/v1/profile`, body).pipe(
      tap((profile) => this.accept(profile)),
      catchError((error: unknown) => {
        if (!(error instanceof HttpErrorResponse)) throw error;
        // Keep the learner's answers locally so the surface adapts even when the save
        // failed. They asked for a beginner roadmap; a network error is not a reason to
        // hand them an advanced one.
        this.profileState.update((current) => ({ ...current, ...changes }));
        this.loadedState.set(true);
        return of(this.profileState());
      }),
    );
  }

  /** Apply an override for this session only, without persisting it. */
  setSkillLevelLocally(skillLevel: UserProfile['skillLevel']): void {
    this.profileState.update((current) => ({ ...current, skillLevel }));
  }

  private accept(profile: UserProfile): void {
    this.profileState.set(profile);
    this.loadedState.set(true);
    // The backend mints the guest id; remembering it here is what keeps an anonymous
    // visitor's profile, uploads and progress attached to one identity.
    this.guestSession.remember(profile.guestSessionId);
  }
}
