import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { GuestSessionService } from '../session/guest-session.service';
import { UserProfileService } from './user-profile.service';

/**
 * The learner's skill profile.
 *
 * The rules worth pinning are about not getting in the way: a failed load must not show
 * the questionnaire to someone who already answered it, and a failed save must not throw
 * away the answers they just gave.
 */
describe('UserProfileService', () => {
  let service: UserProfileService;
  let http: HttpTestingController;
  let session: GuestSessionService;

  const savedProfile = {
    skillLevel: 'BEGINNER_0',
    notationFluency: 'NONE',
    preferredInput: 'TOUCH',
    dailyGoalMinutes: 20,
    onboarded: true,
    isGuest: true,
    guestSessionId: 'guest_abc123def456ghi',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [UserProfileService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UserProfileService);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(GuestSessionService);
    session.sessionId.set(null);
  });

  afterEach(() => http.verify());

  it('does not ask the questionnaire until the profile has actually loaded', () => {
    // Otherwise it flashes up for someone who answered it last week, every visit.
    expect(service.needsOnboarding()).toBeFalse();

    service.load().subscribe();
    http.expectOne(() => true).flush({ ...savedProfile, onboarded: false });

    expect(service.needsOnboarding()).toBeTrue();
  });

  it('does not ask the questionnaire when the profile says it was answered', () => {
    service.load().subscribe();
    http.expectOne(() => true).flush(savedProfile);

    expect(service.needsOnboarding()).toBeFalse();
    expect(service.skillLevel()).toBe('BEGINNER_0');
  });

  it('stays silent when the profile cannot be fetched', () => {
    service.load().subscribe();
    http.expectOne(() => true).error(new ProgressEvent('network'), { status: 0 });

    // A learner with no backend gets a usable surface and the default plan — not an
    // error, and not a questionnaire shown on the strength of a failed request.
    expect(service.needsOnboarding()).toBeFalse();
    expect(service.skillLevel()).toBe('BEGINNER_1');
  });

  it('remembers the identity the backend issues, so uploads and progress stay linked', () => {
    service.load().subscribe();
    http.expectOne(() => true).flush(savedProfile);

    expect(session.sessionId()).toBe('guest_abc123def456ghi');
  });

  it('sends only what changed, so a device switch cannot reset the skill level', () => {
    service.save({ preferredInput: 'MIDI' }).subscribe();

    const request = http.expectOne((req) => req.url.endsWith('/api/v1/profile'));
    expect(request.request.body.preferredInput).toBe('MIDI');
    expect(request.request.body.skillLevel).toBeUndefined();
    request.flush(savedProfile);
  });

  it('keeps the learner’s answers locally when the save fails', () => {
    service.save({ skillLevel: 'BEGINNER_0', onboarded: true }).subscribe();
    http.expectOne(() => true).error(new ProgressEvent('network'), { status: 0 });

    // They asked for a beginner roadmap. A network error is not a reason to hand them
    // an intermediate one.
    expect(service.skillLevel()).toBe('BEGINNER_0');
  });

  it('identifies an anonymous visitor on load when it has a session', () => {
    session.sessionId.set('guest_abc123def456ghi');

    service.load().subscribe();

    http.expectOne((req) =>
      req.urlWithParams.includes('guestSessionId=guest_abc123def456ghi'),
    ).flush(savedProfile);
  });
});
