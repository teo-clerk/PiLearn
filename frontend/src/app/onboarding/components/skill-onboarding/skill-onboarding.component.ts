import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import type {
  NotationFluency,
  SkillLevel,
} from '../../../core/profile/user-profile.model';
import { UserProfileService } from '../../../core/profile/user-profile.service';

interface Choice<T> {
  value: T;
  label: string;
  detail: string;
}

/**
 * Two questions, asked once.
 *
 * Deliberately two and not ten. Every extra question is a chance to close the tab, and
 * these two are the only ones that change what the learner is given: experience picks the
 * practice ladder, and reading picks the visual aids. Everything else — preferred input,
 * daily goal — is discoverable from use and is not worth a form field before someone has
 * heard a note.
 *
 * Skippable for the same reason. Someone who skips gets the default ladder, which is a
 * reasonable plan, rather than being held at a gate.
 */
@Component({
  selector: 'app-skill-onboarding',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './skill-onboarding.component.html',
  styleUrl: './skill-onboarding.component.css',
})
export class SkillOnboardingComponent {
  private readonly profileService = inject(UserProfileService);

  /** Emitted once the learner has answered or skipped. */
  readonly done = output<void>();

  readonly experienceChoices: Choice<SkillLevel>[] = [
    {
      value: 'BEGINNER_0',
      label: 'Never touched a piano',
      detail: "We'll start with the rhythm, then find the keys one at a time.",
    },
    {
      value: 'BEGINNER_1',
      label: 'I know some basics',
      detail: 'One hand at a time first, with the notes labelled.',
    },
    {
      value: 'INTERMEDIATE',
      label: "I'm an intermediate player",
      detail: 'Straight to hands together, phrase by phrase.',
    },
    {
      value: 'ADVANCED',
      label: 'I play fluently',
      detail: 'Full phrases at tempo, no training wheels.',
    },
  ];

  readonly readingChoices: Choice<NotationFluency>[] = [
    { value: 'NONE', label: 'No — I need visual aids', detail: 'Note names stay on the keys.' },
    { value: 'SOME', label: 'A little', detail: 'Labels while learning, off for the final run.' },
    { value: 'FLUENT', label: 'Yes, I read fluently', detail: 'Just the score.' },
  ];

  readonly experience = signal<SkillLevel | null>(null);
  readonly reading = signal<NotationFluency | null>(null);
  readonly saving = signal(false);

  /** Both answered — the button stays inert until then rather than saving half a profile. */
  readonly canSubmit = computed(
    () => this.experience() !== null && this.reading() !== null && !this.saving(),
  );

  /** A plain-language preview of the plan they are about to get. */
  readonly preview = computed(() => {
    const level = this.experience();
    if (level === null) return '';
    return this.experienceChoices.find((choice) => choice.value === level)?.detail ?? '';
  });

  chooseExperience(level: SkillLevel): void {
    this.experience.set(level);

    // A complete novice who has never played almost certainly cannot read notation
    // either. Pre-selecting it saves them a question they can still override — and
    // getting it wrong costs only a label on a key.
    if (level === 'BEGINNER_0' && this.reading() === null) {
      this.reading.set('NONE');
    }
    if (level === 'ADVANCED' && this.reading() === null) {
      this.reading.set('FLUENT');
    }
  }

  chooseReading(fluency: NotationFluency): void {
    this.reading.set(fluency);
  }

  submit(): void {
    const skillLevel = this.experience();
    const notationFluency = this.reading();
    if (skillLevel === null || notationFluency === null || this.saving()) return;

    this.saving.set(true);
    this.profileService
      .save({ skillLevel, notationFluency, onboarded: true })
      .subscribe({
        // The service already falls back to keeping the answers locally, so both paths
        // end the same way: the learner gets the plan they asked for.
        next: () => this.finish(),
        error: () => this.finish(),
      });
  }

  /**
   * Skip, and stop asking.
   *
   * Marked onboarded so the questionnaire does not reappear on every visit — someone
   * who declined once has answered the question of whether they want to answer.
   */
  skip(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.profileService.save({ onboarded: true }).subscribe({
      next: () => this.finish(),
      error: () => this.finish(),
    });
  }

  private finish(): void {
    this.saving.set(false);
    this.done.emit();
  }
}
