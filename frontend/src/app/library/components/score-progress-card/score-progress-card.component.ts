import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { LibraryEntry } from '../../services/my-scores.service';

/**
 * One score in the learner's library, with its progress.
 *
 * Its own component rather than markup inside the list: the card owns real presentation
 * logic — whether the score can be opened at all, how to phrase "last practised", what a
 * non-playable status means — and that is enough behaviour to be worth naming and testing
 * on its own.
 */
@Component({
  selector: 'app-score-progress-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './score-progress-card.component.html',
  styleUrl: './score-progress-card.component.css',
})
export class ScoreProgressCardComponent {
  readonly entry = input.required<LibraryEntry>();

  readonly resume = output<LibraryEntry>();

  /**
   * Whether the score can be opened.
   *
   * REVIEW_REQUIRED counts: a partially recognised score is still practisable, and the
   * practice surface carries its own review banner. Refusing to open it would strand the
   * learner on a card with no way forward.
   */
  readonly isPlayable = computed(() => {
    const status = this.entry().status;
    return status === 'READY' || status === 'REVIEW_REQUIRED';
  });

  readonly progressPercent = computed(() => Math.round(this.entry().progress * 100));

  readonly statusLabel = computed(() => {
    switch (this.entry().status) {
      case 'QUEUED':
        return 'Waiting to be processed';
      case 'PROCESSING':
        return 'Reading the notes…';
      case 'REVIEW_REQUIRED':
        return 'Ready — some bars need review';
      case 'FAILED':
        return 'Could not be processed';
      default:
        return '';
    }
  });

  readonly actionLabel = computed(() =>
    this.entry().progress > 0 ? 'Resume practice' : 'Start practising',
  );

  /** Relative, because "3 days ago" is what a learner actually wants to know. */
  readonly lastPractised = computed(() => {
    const stamp = this.entry().lastPracticedAt;
    if (!stamp) return 'Not started yet';

    const then = new Date(stamp).getTime();
    if (Number.isNaN(then)) return 'Not started yet';

    const days = Math.floor((Date.now() - then) / 86_400_000);
    if (days <= 0) return 'Practised today';
    if (days === 1) return 'Practised yesterday';
    if (days < 7) return `Practised ${days} days ago`;
    if (days < 30) return `Practised ${Math.floor(days / 7)} weeks ago`;
    return `Practised ${Math.floor(days / 30)} months ago`;
  });

  onResume(): void {
    if (this.isPlayable()) this.resume.emit(this.entry());
  }
}
