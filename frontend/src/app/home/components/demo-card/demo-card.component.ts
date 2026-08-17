import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { DemoCatalogEntry } from '../../../core/score/demo-score.service';

/**
 * One piece on the landing page's demo shelf.
 *
 * Extracted from `HomeComponent`: the card carries a substantial stylesheet of its own,
 * and inline it pushed the landing page past the per-component CSS budget. It is also
 * the one part of the page that is genuinely repeated.
 */
@Component({
  selector: 'app-demo-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="card">
      <div class="card__head">
        <h3 class="card__title">{{ demo().title }}</h3>
        <p class="card__composer">{{ demo().composer }}</p>
      </div>

      <dl class="meta">
        <div><dt>Bars</dt><dd>{{ demo().measures }}</dd></div>
        <div><dt>Chunks</dt><dd>{{ demo().chunks }}</dd></div>
        <div><dt>Key</dt><dd>{{ demo().keySignature }}</dd></div>
        <div><dt>Tempo</dt><dd>{{ demo().tempoBpm }} bpm</dd></div>
      </dl>

      <div class="tags">
        <span class="tag" [class]="'tag ' + difficultyClass()">{{ difficultyLabel() }}</span>
        <span class="tag tag--time">{{ estimate() }}</span>
      </div>

      <button type="button" class="play" (click)="practice.emit(demo().slug)">
        Practice now
      </button>
    </article>
  `,
  styles: [
    `
      :host { display: block; height: 100%; }

      .card {
        display: flex; flex-direction: column; gap: 0.85rem; height: 100%;
        padding: 1.2rem;
        border: 1px solid #252a32;
        border-radius: 14px;
        background: #14171c;
        transition: border-color 140ms, transform 140ms;
      }
      .card:hover { border-color: #3f3f9a; transform: translateY(-2px); }

      .card__title { margin: 0 0 0.15rem; font-size: 1rem; line-height: 1.3; color: #edeff2; }
      .card__composer { margin: 0; font-size: 0.78rem; color: #98a0ac; }

      .meta { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0.4rem 0.8rem; margin: 0; }
      .meta div { display: flex; justify-content: space-between; gap: 0.5rem; }
      .meta dt { font-size: 0.72rem; color: #98a0ac; }
      .meta dd { margin: 0; font-size: 0.72rem; color: #edeff2; font-variant-numeric: tabular-nums; }

      .tags { display: flex; gap: 0.35rem; flex-wrap: wrap; }
      .tag {
        padding: 0.15rem 0.5rem; border-radius: 999px;
        font-size: 0.68rem; font-weight: 600;
        background: #191d23; color: #98a0ac;
      }
      .tag--beginner { background: rgb(34 197 94 / 16%); color: #86efac; }
      .tag--easy { background: rgb(59 130 246 / 16%); color: #93c5fd; }
      .tag--intermediate { background: rgb(234 179 8 / 16%); color: #fde047; }
      .tag--advanced { background: rgb(239 68 68 / 16%); color: #fca5a5; }

      .play {
        margin-top: auto;
        padding: 0.6rem 1rem;
        border: 1px solid rgb(99 102 241 / 40%);
        border-radius: 10px;
        background: rgb(99 102 241 / 16%);
        color: #edeff2;
        font-size: 0.85rem; font-weight: 600; cursor: pointer;
        transition: background 140ms;
      }
      .play:hover { background: rgb(99 102 241 / 28%); }

      @media (max-width: 720px) { .meta { grid-template-columns: 1fr; } }
      @media (prefers-reduced-motion: reduce) {
        .card, .play { transition: none; }
        .card:hover { transform: none; }
      }
    `,
  ],
})
export class DemoCardComponent {
  readonly demo = input.required<DemoCatalogEntry>();
  readonly practice = output<string>();

  /** Grades are 0..8, RCM-like, from the difficulty analyser. */
  readonly difficultyLabel = computed(() => {
    const grade = this.demo().grade;
    if (grade < 2) return 'Beginner';
    if (grade < 4) return 'Easy';
    if (grade < 6) return 'Intermediate';
    return 'Advanced';
  });

  readonly difficultyClass = computed(() => `tag--${this.difficultyLabel().toLowerCase()}`);

  readonly estimate = computed(() => {
    const minutes = this.demo().estimatedMinutes;
    if (minutes < 60) return `~${minutes} min`;
    return `~${Math.round((minutes / 60) * 10) / 10} h`;
  });
}
