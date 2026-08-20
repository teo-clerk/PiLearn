import { ChangeDetectionStrategy, Component, output } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * A gentle nudge for a visitor practising without an account.
 *
 * Deliberately dismissible and non-blocking: the score already loaded and the learner is
 * about to play. Interrupting that with a modal would trade the one moment the product
 * has proven itself for a sign-up form. The banner states what is actually at stake —
 * progress is not saved — rather than asking for an account on principle.
 */
@Component({
  selector: 'app-guest-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './guest-banner.component.html',
  styleUrl: './guest-banner.component.css',
})
export class GuestBannerComponent {
  readonly dismiss = output<void>();
}
