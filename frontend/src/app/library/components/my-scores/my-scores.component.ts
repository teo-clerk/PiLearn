import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { type LibraryEntry, MyScoresService } from '../../services/my-scores.service';
import { ScoreProgressCardComponent } from '../score-progress-card/score-progress-card.component';
import { UserProfileService } from '../../../core/profile/user-profile.service';
import { SKILL_LEVEL_LABELS } from '../../../core/profile/user-profile.model';

/**
 * The learner's own scores, with how far they have got.
 *
 * Distinct from the browse views next door, which show the shared catalogue. This is the
 * "where was I?" screen, so it is ordered by what the learner is most likely to want to
 * open: unfinished pieces first, then untouched uploads, then finished ones.
 */
@Component({
  selector: 'app-my-scores',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ScoreProgressCardComponent],
  templateUrl: './my-scores.component.html',
  styleUrl: './my-scores.component.css',
})
export class MyScoresComponent {
  private readonly router = inject(Router);
  private readonly scores = inject(MyScoresService);
  private readonly profileService = inject(UserProfileService);

  readonly isLoading = this.scores.isLoading;
  readonly error = this.scores.error;
  readonly isEmpty = this.scores.isEmpty;

  readonly isGuest = computed(() => this.profileService.profile().isGuest);
  readonly skillLabel = computed(
    () => SKILL_LEVEL_LABELS[this.profileService.profile().skillLevel],
  );

  /**
   * Cards in the order a learner would look for them.
   *
   * Alphabetical would be tidier and less useful: the piece you were working on
   * yesterday is the one you came here to reopen.
   */
  readonly entries = computed<readonly LibraryEntry[]>(() => {
    const rank = (entry: LibraryEntry): number => {
      if (entry.mastered) return 2;
      return entry.progress > 0 ? 0 : 1;
    };

    return [...this.scores.entries()].sort((a, b) => {
      const byRank = rank(a) - rank(b);
      if (byRank !== 0) return byRank;
      return (b.lastPracticedAt ?? b.uploadedAt ?? '').localeCompare(
        a.lastPracticedAt ?? a.uploadedAt ?? '',
      );
    });
  });

  constructor() {
    this.scores.load();
    // The header greets the learner by level, which needs the profile loaded.
    this.profileService.load().subscribe();
  }

  retry(): void {
    this.scores.load();
  }



  /** "Resume practice" — the whole point of the screen. */
  resume(entry: LibraryEntry): void {
    void this.router.navigate(['/practice', entry.scoreId]);
  }

}
