import { ChangeDetectionStrategy, Component, ViewChild, signal, effect, type EffectRef, OnDestroy, PLATFORM_ID, inject, Input, AfterViewInit } from "@angular/core";
import { isPlatformBrowser } from '@angular/common';
import { MidiServiceService } from "../../../shared/services/midi-service.service";
import { VirtualKeyboardComponent, type KeyPressEvent } from '../../../practice/components/virtual-keyboard/virtual-keyboard.component';
import { nameToMidi } from '../../../practice/components/virtual-keyboard/keyboard-geometry';
import { PlayerKeyboardService } from "../../service/player-keyboard.service";


@Component({
  selector: 'app-keyboard',
  imports: [VirtualKeyboardComponent],
  templateUrl: './keyboard.component.html',
  styleUrl: './keyboard.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KeyboardComponent implements OnDestroy, AfterViewInit {

  private platformId = inject(PLATFORM_ID);
  keyPressed = signal<{ key: number; timestamp: number } | null>(null);
  keyReleased = signal<{ key: number; timestamp: number } | null>(null);

  private effectRefs: EffectRef[] = [];

  @ViewChild(VirtualKeyboardComponent)
  virtualKeyboard?: VirtualKeyboardComponent;

  @Input() minKey: string = 'A0';
  @Input() maxKey: string = 'C8';
  @Input() keyHeight?: number | null = null;


  constructor(
    private keyboardService: PlayerKeyboardService,
    private midiService: MidiServiceService
  ) {
    this.registerWithMidiService();
  }


  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }
    // Hand the rendered component to PlayerKeyboardService, which drives velocity-graded
    // highlighting through fillKey/clearKey exactly as it did with pianokeys.
    if (this.virtualKeyboard) {
      this.keyboardService.setPianoKeys(this.virtualKeyboard);
    }
  }

  /**
   * Resolve the key range from inputs, falling back to stored user preferences.
   *
   * Reads localStorage lazily rather than in a field initialiser: this component is
   * rendered under SSR, where localStorage does not exist.
   */
  private storedPreference(key: 'leftmostKey' | 'rightmostKey', fallback: number): number {
    if (!isPlatformBrowser(this.platformId)) return fallback;
    try {
      const raw = localStorage.getItem('preferences');
      const parsed = raw ? JSON.parse(raw) : {};
      const value = Number(parsed?.[key]);
      return Number.isFinite(value) && value > 0 ? value : fallback;
    } catch {
      return fallback;
    }
  }

  lowestMidi(): number {
    return nameToMidi(this.minKey) ?? this.storedPreference('leftmostKey', 21);
  }

  highestMidi(): number {
    return nameToMidi(this.maxKey) ?? this.storedPreference('rightmostKey', 108);
  }

  resolvedKeyHeight(): number {
    return this.keyHeight ?? 140;
  }

  /**
   * Note: the component emits TRUE MIDI numbers. The pianokeys callbacks it replaces
   * reported note numbers an octave low, which every call site corrected with
   * `12 + Number(keyInfo.note)`. That correction is gone along with the offset.
   */
  onKeyDown(event: KeyPressEvent): void {
    this.keyPressed.set({ key: event.midi, timestamp: Date.now() });
    this.keyboardService.press(event.name, event.midi);
  }

  onKeyUp(event: KeyPressEvent): void {
    this.keyReleased.set({ key: event.midi, timestamp: Date.now() });
    this.keyboardService.release(event.name, event.midi);
  }

  private registerWithMidiService(): void {
    // Effect pour écouter les touches pressées
    const pressedEffect = effect(() => {
      const keyEvent = this.keyPressed();
      if (keyEvent !== null) {
        this.midiService.press(keyEvent.key, 255);
      }
    });
    this.effectRefs.push(pressedEffect);

    // Effect pour écouter les touches relâchées
    const releasedEffect = effect(() => {
      const keyEvent = this.keyReleased();
      if (keyEvent !== null) {
        this.midiService.release(keyEvent.key);
      }
    });
    this.effectRefs.push(releasedEffect);
  }

  ngOnDestroy(): void {
    // Nettoyer les effects pour éviter les fuites mémoire
    for (const effectRef of this.effectRefs) {
      effectRef.destroy();
    }
    this.effectRefs = [];
  }

}