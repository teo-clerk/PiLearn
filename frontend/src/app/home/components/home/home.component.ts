import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  PLATFORM_ID,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import {
  type DemoCatalogEntry,
  DemoScoreService,
} from '../../../core/score/demo-score.service';
import { UserProfileService } from '../../../core/profile/user-profile.service';
import { SkillOnboardingComponent } from '../../../onboarding/components/skill-onboarding/skill-onboarding.component';
import { DemoCardComponent } from '../demo-card/demo-card.component';
import { HomeHeroComponent } from '../home-hero/home-hero.component';

interface ValuePill {
  icon: string;
  label: string;
}

interface HowItWorksStep {
  index: number;
  title: string;
  body: string;
  icon: string;
}

const VALUE_PILLS: readonly ValuePill[] = [
  { icon: '📄', label: 'Instant PDF / OMR ingestion' },
  { icon: '🧩', label: 'Intelligent chunk practice' },
  { icon: '⏱️', label: 'Real-time timing feedback' },
  { icon: '🎹', label: 'MIDI, QWERTY & touch' },
];

const HOW_IT_WORKS: readonly HowItWorksStep[] = [
  {
    index: 1,
    title: 'Upload & transcribe',
    body:
      'Drop in a PDF. Two OMR engines read the notation, and every page is reconciled — ' +
      'if a page cannot be read, you are told rather than quietly given a shorter piece.',
    icon: '📄',
  },
  {
    index: 2,
    title: 'Hands isolation',
    body:
      'Work one hand at a time while the app plays the other, so you keep the harmonic ' +
      'context instead of drilling a line in the dark.',
    icon: '🖐️',
  },
  {
    index: 3,
    title: 'Chunk looping',
    body:
      'The piece is cut into short passages at real phrase boundaries — never mid-phrase ' +
      'to hit a round number — and ordered so the hard bars get isolated.',
    icon: '🧩',
  },
  {
    index: 4,
    title: 'Tempo ramp & fluency',
    body:
      'Speed climbs a rung each time you play a clean run, and steps back down when a ' +
      'rung is too fast. Then the whole piece, at tempo, without the guide.',
    icon: '📈',
  },
];

/**
 * Landing page.
 *
 * Replaces the legacy hero, which opened on an abstract WebGL background and the phrase
 * "Take a seat" — evocative, but it never said what the product does, and a visitor had
 * no route into it. This one leads with the claim, then a demo they can play in one
 * click, because the fastest way to explain a practice tool is to let someone practise.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, DemoCardComponent, HomeHeroComponent, SkillOnboardingComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeComponent {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly router = inject(Router);
  private readonly demoService = inject(DemoScoreService);
  private readonly title = inject(Title);
  private readonly profileService = inject(UserProfileService);
  private readonly meta = inject(Meta);

  readonly pills = VALUE_PILLS;
  readonly steps = HOW_IT_WORKS;

  /** Demo shelf. Failure degrades to an empty shelf rather than breaking the page. */
  readonly demos = toSignal(
    this.demoService.catalog().pipe(catchError(() => of([] as DemoCatalogEntry[]))),
    { initialValue: [] as DemoCatalogEntry[] },
  );

  readonly hasDemos = computed(() => this.demos().length > 0);

  /**
   * Ask the two skill questions, once.
   *
   * Only in the browser, and only once the profile has actually loaded — asking during
   * SSR would flash the dialog to everyone, and asking before the answer arrives would
   * show it to someone who answered last week.
   */
  private readonly dismissed = signal(false);
  readonly showOnboarding = computed(
    () => !this.dismissed() && this.profileService.needsOnboarding(),
  );

  onOnboardingDone(): void {
    this.dismissed.set(true);
  }

  constructor() {
    if (isPlatformBrowser(this.platformId)) {
      this.profileService.load().subscribe();
    }

    this.title.setTitle('PiLearn — learn any piano piece, step by step');
    this.meta.updateTag({
      name: 'description',
      content:
        'Upload sheet music as a PDF and get a step-by-step practice plan: hands ' +
        'separate, phrase-sized chunks, and a tempo ramp with real-time feedback.',
    });
  }

  practice(slug: string): void {
    void this.router.navigate(['/practice', slug]);
  }

  /**
   * Hand the accepted file to the import flow through history state — a File is not
   * serialisable into a URL, and re-picking it after navigation would be a pointless
   * second step.
   */
  onFileAccepted(file: File): void {
    if (!isPlatformBrowser(this.platformId)) return;
    void this.router.navigate(['/import'], { state: { droppedFile: file } });
  }
}
