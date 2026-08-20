import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { GuestSessionService } from '../../core/session/guest-session.service';
import { MyScoresService } from './my-scores.service';

/**
 * The learner's own scores.
 *
 * What is pinned here is mostly about honesty toward the learner: an empty library and a
 * failed request must not look the same, and a checkpoint that fails to save must not
 * interrupt practice.
 */
describe('MyScoresService', () => {
  let service: MyScoresService;
  let http: HttpTestingController;
  let session: GuestSessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [MyScoresService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MyScoresService);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(GuestSessionService);
    session.sessionId.set(null);
  });

  afterEach(() => http.verify());

  it('identifies an anonymous visitor by their session', () => {
    session.sessionId.set('guest_abc123def456ghi');

    service.load();

    // Without this the backend cannot tell which guest is asking, and every anonymous
    // visitor would be handed the same (or an empty) library.
    const request = http.expectOne((req) =>
      req.urlWithParams.includes('guestSessionId=guest_abc123def456ghi'),
    );
    request.flush([]);
  });

  it('asks without a session when there is nothing to identify', () => {
    service.load();

    const request = http.expectOne((req) => !req.urlWithParams.includes('guestSessionId'));
    request.flush([]);
  });

  it('reports an empty library as empty, not as an error', () => {
    service.load();
    http.expectOne(() => true).flush([]);

    // A first-time visitor genuinely has nothing. Showing an error would make the page
    // look broken rather than new.
    expect(service.isEmpty()).toBeTrue();
    expect(service.error()).toBeNull();
  });

  it('distinguishes an unreachable server from an empty library', () => {
    service.load();
    http.expectOne(() => true).error(new ProgressEvent('network'), { status: 0 });

    // These call for completely different next actions from the learner, so they must
    // never render the same.
    expect(service.error()).toContain('Could not reach the server');
    expect(service.isEmpty()).toBeFalse();
  });

  it('separates unfinished pieces from mastered ones', () => {
    service.load();
    http.expectOne(() => true).flush([
      entry({ scoreId: 'a', progress: 0.5, mastered: false }),
      entry({ scoreId: 'b', progress: 1, mastered: true }),
      entry({ scoreId: 'c', progress: 0, mastered: false }),
    ]);

    expect(service.inProgress().map((e) => e.scoreId)).toEqual(['a']);
    expect(service.mastered().map((e) => e.scoreId)).toEqual(['b']);
  });

  it('sends the guest session with a progress checkpoint', () => {
    session.sessionId.set('guest_abc123def456ghi');

    service.recordProgress('score-1', { stageIndex: 3, tempoPercent: 75 });

    const request = http.expectOne((req) =>
      req.url.endsWith('/api/v1/scores/score-1/progress'),
    );
    expect(request.request.body.guestSessionId).toBe('guest_abc123def456ghi');
    expect(request.request.body.stageIndex).toBe(3);
    request.flush(null);
  });

  it('swallows a failed checkpoint rather than interrupting practice', () => {
    service.recordProgress('score-1', { stageIndex: 3 });

    // The learner loses their place, not their work, and the next checkpoint carries
    // the same information. Throwing here would surface an error mid-piece.
    expect(() =>
      http
        .expectOne((req) => req.url.endsWith('/api/v1/scores/score-1/progress'))
        .error(new ProgressEvent('fail')),
    ).not.toThrow();
  });
});

/** A library entry with only the fields a test cares about spelled out. */
function entry(overrides: Record<string, unknown>): Record<string, unknown> {
  return {
    scoreId: 'x',
    title: 'Piece',
    composer: 'Composer',
    status: 'READY',
    difficulty: null,
    difficultyLabel: null,
    measureCount: 16,
    progress: 0,
    stagesCompleted: 0,
    totalStages: 8,
    stageIndex: 0,
    chunkOrdinal: 0,
    tempoPercent: 100,
    masteryScore: null,
    mastered: false,
    lastPracticedAt: null,
    uploadedAt: null,
    stageSummary: 'Not started',
    ...overrides,
  };
}
