import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera, BlinkClip } from '../../models/blink.model';

/**
 * Kamera-Ansicht fuer das Wandtablet: Standbilder, Scharf-Status und
 * Schnappschuss. BEWUSST OHNE Scharf/Unscharf-Steuerung - die KIOSK-Rolle
 * darf nicht schalten, und der Weg dorthin soll auf dem frei zugaenglichen
 * Tablet gar nicht erst sichtbar sein (Muster Nuki: nur verriegeln).
 */
@Component({
  selector: 'app-tablet-cameras',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-cameras.component.html',
  styleUrl: './tablet-cameras.component.scss'
})
export class TabletCamerasComponent implements OnInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60 * 1000;

  /**
   * Bewusst nur die lesenden Faehigkeiten plus Schnappschuss: setCameraArmed und
   * setSystemArmed sind hier NICHT erreichbar, ein versehentlicher Aufruf
   * scheitert schon beim Compilieren. Das Wandtablet laeuft als KIOSK und ist
   * frei zugaenglich - die Kameras duerfen von dort nicht unscharf geschaltet
   * werden (Muster Nuki: das Tablet darf nur verriegeln, nie oeffnen).
   */
  private readonly blinkService: Pick<
    BlinkService, 'getCameras' | 'getClips' | 'takeSnapshot' | 'thumbnailUrl' | 'clipUrl'
  > = inject(BlinkService);
  private refreshTimer: number | null = null;

  cameras: BlinkCamera[] = [];
  error: string | null = null;
  loaded = false;

  private readonly cacheKeys = new Map<string, number>();
  private readonly snapshotBusy = new Set<string>();
  readonly clips = new Map<string, BlinkClip[]>();
  readonly expandedCameras = new Set<string>();
  playingClipUrl: string | null = null;

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(
      () => this.load(true), TabletCamerasComponent.REFRESH_INTERVAL_MS);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  cacheKey(cameraId: string): number {
    const existing = this.cacheKeys.get(cameraId);
    if (existing === undefined) {
      this.cacheKeys.set(cameraId, 1);
      return 1;
    }
    return existing;
  }

  thumbnailUrl(camera: BlinkCamera): string {
    return this.blinkService.thumbnailUrl(camera.cameraId, this.cacheKey(camera.cameraId));
  }

  isSnapshotBusy(cameraId: string): boolean {
    return this.snapshotBusy.has(cameraId);
  }

  takeSnapshot(camera: BlinkCamera): void {
    if (this.snapshotBusy.has(camera.cameraId)) {
      return;
    }
    this.snapshotBusy.add(camera.cameraId);
    this.blinkService.takeSnapshot(camera.cameraId).subscribe({
      next: () => {
        this.snapshotBusy.delete(camera.cameraId);
        // Der Cache-Buster wird erst NACH einer erfolgreichen Antwort erhoeht,
        // damit bei einem Fehlschlag das bisherige Bild stehen bleibt.
        this.cacheKeys.set(camera.cameraId, this.cacheKey(camera.cameraId) + 1);
      },
      error: () => this.snapshotBusy.delete(camera.cameraId)
    });
  }

  toggleClips(camera: BlinkCamera): void {
    if (this.expandedCameras.has(camera.cameraId)) {
      this.expandedCameras.delete(camera.cameraId);
      return;
    }
    this.expandedCameras.add(camera.cameraId);
    this.blinkService.getClips(camera.cameraId).subscribe({
      next: clips => this.clips.set(camera.cameraId, clips),
      error: () => this.clips.set(camera.cameraId, [])
    });
  }

  playClip(camera: BlinkCamera, clip: BlinkClip): void {
    this.playingClipUrl = this.blinkService.clipUrl(camera.cameraId, clip.clipId);
  }

  closePlayer(): void {
    this.playingClipUrl = null;
  }

  private load(silent: boolean): void {
    this.blinkService.getCameras().subscribe({
      next: cameras => {
        this.cameras = cameras;
        this.error = null;
        this.loaded = true;
      },
      error: () => {
        // Nur der Erstabruf meldet einen Fehler - ein fehlgeschlagener
        // Hintergrund-Refresh behaelt stumm den letzten Stand (Wandanzeige).
        if (!silent || !this.loaded) {
          this.error = 'Kameras nicht verfügbar.';
        }
      }
    });
  }
}
