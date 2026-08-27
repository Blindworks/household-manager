import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera, BlinkClip } from '../../models/blink.model';

/** Kameras eines Sync-Moduls, gruppiert fuer die Anzeige. */
export interface CameraGroup {
  syncName: string;
  syncArmed: boolean;
  cameras: BlinkCamera[];
}

/**
 * Blink-Kamera-Dashboard: Standbilder, Scharf/Unscharf (Kamera + System),
 * Schnappschuss und Clip-Wiedergabe. Selbst-Refresh 60 s; nur der Erstabruf
 * meldet einen Fehler, spaetere behalten den letzten Stand.
 */
@Component({
  selector: 'app-cameras',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './cameras.component.html',
  styleUrl: './cameras.component.scss'
})
export class CamerasComponent implements OnInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60 * 1000;

  private readonly blinkService = inject(BlinkService);
  private refreshTimer: number | null = null;

  groups: CameraGroup[] = [];
  error: string | null = null;
  /** Backend meldet 400 = keine Blink-Anmeldung; Login lebt auf /vision. */
  notLoggedIn = false;
  loaded = false;

  /** Cache-Buster je Kamera; ein Schnappschuss erneuert ihn nach Erfolg. */
  private readonly cacheKeys = new Map<string, number>();
  private readonly snapshotBusy = new Set<string>();
  private readonly armBusy = new Set<string>();
  /** Aufgeklappte Clip-Listen je Kamera. */
  readonly clips = new Map<string, BlinkClip[]>();
  readonly expandedCameras = new Set<string>();
  /** Gerade abgespielter Clip (URL fuers <video>). */
  playingClipUrl: string | null = null;
  /** Kameras, deren Standbild-Abruf fehlgeschlagen ist (Platzhalter statt kaputtem Bild). */
  private readonly thumbnailErrors = new Set<string>();

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(() => this.reload(), CamerasComponent.REFRESH_INTERVAL_MS);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  reload(): void {
    this.load(true);
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

  isArmBusy(key: string): boolean {
    return this.armBusy.has(key);
  }

  toggleCamera(camera: BlinkCamera): void {
    this.armBusy.add(camera.cameraId);
    this.blinkService.setCameraArmed(camera.cameraId, !camera.armed).subscribe({
      next: () => {
        this.armBusy.delete(camera.cameraId);
        this.load(true);
      },
      error: () => {
        this.armBusy.delete(camera.cameraId);
        this.error = 'Schalten fehlgeschlagen.';
      }
    });
  }

  toggleSystem(group: CameraGroup): void {
    const key = `sync:${group.syncName}`;
    this.armBusy.add(key);
    this.blinkService.setSystemArmed(group.syncName, !group.syncArmed).subscribe({
      next: () => {
        this.armBusy.delete(key);
        this.load(true);
      },
      error: () => {
        this.armBusy.delete(key);
        this.error = 'Schalten fehlgeschlagen.';
      }
    });
  }

  takeSnapshot(camera: BlinkCamera): void {
    if (this.snapshotBusy.has(camera.cameraId)) {
      return;
    }
    this.snapshotBusy.add(camera.cameraId);
    this.blinkService.takeSnapshot(camera.cameraId).subscribe({
      next: () => {
        this.snapshotBusy.delete(camera.cameraId);
        this.cacheKeys.set(camera.cameraId, this.cacheKey(camera.cameraId) + 1);
        // Ein frischer Schnappschuss ist der Weg zum ersten Bild — der Platzhalter muss weichen.
        this.thumbnailErrors.delete(camera.cameraId);
      },
      error: () => {
        // Altes Standbild bleibt stehen (kein Cache-Buster-Update).
        this.snapshotBusy.delete(camera.cameraId);
        this.error = 'Schnappschuss fehlgeschlagen.';
      }
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

  onThumbnailError(cameraId: string): void {
    this.thumbnailErrors.add(cameraId);
  }

  hasThumbnailError(cameraId: string): boolean {
    return this.thumbnailErrors.has(cameraId);
  }

  playLastMotion(camera: BlinkCamera): void {
    if (camera.lastMotionClipId) {
      this.playingClipUrl = this.blinkService.clipUrl(camera.cameraId, camera.lastMotionClipId);
    }
  }

  closePlayer(): void {
    this.playingClipUrl = null;
  }

  private load(silent: boolean): void {
    this.blinkService.getCameras().subscribe({
      next: cameras => {
        this.groups = this.groupBySync(cameras);
        this.error = null;
        this.notLoggedIn = false;
        this.loaded = true;
      },
      error: err => {
        if (!silent || !this.loaded) {
          this.notLoggedIn = err?.status === 400;
          this.error = this.notLoggedIn
            ? 'Nicht bei Blink angemeldet.'
            : 'Kameras nicht verfügbar.';
        }
      }
    });
  }

  private groupBySync(cameras: BlinkCamera[]): CameraGroup[] {
    const groups = new Map<string, CameraGroup>();
    for (const camera of cameras) {
      let group = groups.get(camera.syncName);
      if (!group) {
        group = { syncName: camera.syncName, syncArmed: camera.syncArmed, cameras: [] };
        groups.set(camera.syncName, group);
      }
      group.cameras.push(camera);
    }
    return [...groups.values()];
  }
}
