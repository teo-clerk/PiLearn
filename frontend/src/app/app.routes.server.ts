import { RenderMode, type ServerRoute } from '@angular/ssr';

/**
 * Render mode per route.
 *
 * Anything that touches Web Audio, WebMIDI, localStorage or measures the DOM must be
 * `RenderMode.Client`. Rendering those on the Node server does not merely waste work —
 * the component waits on an API that never resolves outside a browser, so the request
 * hangs instead of completing.
 *
 * Every path here must correspond to a real route in `app.routes.ts`; the build fails
 * on entries that match nothing, which is how a stale server route gets caught.
 */
export const serverRoutes: ServerRoute[] = [
  // Practice surface: Tone.js, SpessaSynth, WebMIDI, OSMD measurement.
  { path: 'practice/:scoreId', renderMode: RenderMode.Client },

  // Upload flow: File/DataTransfer APIs and a live progress stream.
  { path: 'import', renderMode: RenderMode.Client },
  { path: 'import/**', renderMode: RenderMode.Client },

  // Legacy workbench — same browser-only dependencies.
  { path: 'work', renderMode: RenderMode.Client },
  { path: 'work/**', renderMode: RenderMode.Client },
  { path: 'workbench', renderMode: RenderMode.Client },
  { path: 'workbench/**', renderMode: RenderMode.Client },
  { path: 'desktop', renderMode: RenderMode.Client },
  { path: 'desktop/**', renderMode: RenderMode.Client },

  // Exercises generate MIDI in-browser and write to localStorage.
  { path: 'exercises', renderMode: RenderMode.Client },
  { path: 'exercises/**', renderMode: RenderMode.Client },

  // Everything else is server-rendered for first paint and SEO.
  { path: '**', renderMode: RenderMode.Server },
];
