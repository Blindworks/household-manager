import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { BlinkService } from '../../services/blink.service';
import { BlinkCamera, BlinkClip } from '../../models/blink.model';

/** Kameras eines Sync-Moduls, gruppiert fuer die Anzeige. */
export interface CameraGroup {
  syncName: string;
  syncArmed: boolean;
  cameras: BlinkCamera[];
}

/** Ziel eines angefragten Unscharfschaltens (Kamera oder ganzes Sync-Modul). */
export interface DisarmRequest {
  kind: 'camera' | 'system';
  id: string;       // cameraId bzw. syncName
  name: string;     // Anzeigename fuer den Dialogtext
}

/**
 * Kamera-Ansicht fuer das Wandtablet: Standbilder, Gruppierung nach
 * Sync-Modul, Bewegungsanzeige, Schnappschuss UND Scharf/Unscharf-Steuerung.
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
   * REVISION 2026-08-27 (Spec blink-bewegung-und-tablet-schalten): Das Tablet
   * darf jetzt in beide Richtungen schalten — Nutzerentscheidung. Der Schutz
   * gegen Versehen ist der Bestaetigungsdialog beim Unscharfschalten
   * (Muster confirm_required); die frueher hier begruendete Compiler-Sperre
   * gegen setCameraArmed/setSystemArmed ist damit bewusst aufgehoben.
   */
  private readonly blinkService: Pick<
    BlinkService, 'getCameras' | 'getClips' | 'takeSnapshot' | 'thumbnailUrl'
    | 'clipUrl' | 'setCameraArmed' | 'setSystemArmed'
  > = inject(BlinkService);
  private refreshTimer: number | null = null;

  cameras: BlinkCamera[] = [];
  groups: CameraGroup[] = [];
  error: string | null = null;
  loaded = false;
  pendingDisarm: DisarmRequest | null = null;

  private readonly cacheKeys = new Map<string, number>();
  private readonly snapshotBusy = new Set<string>();
  private readonly armBusy = new Set<string>();
  private readonly thumbnailErrors = new Set<string>();
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

  isArmBusy(key: string): boolean {
    return this.armBusy.has(key);
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
        // Ein frischer Schnappschuss ist der Weg zum ersten Bild — der Platzhalter muss weichen.
        this.thumbnailErrors.delete(camera.cameraId);
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

  toggleCamera(camera: BlinkCamera): void {
    if (camera.armed) {
      this.requestDisarm({ kind: 'camera', id: camera.cameraId, name: camera.name });
    } else {
      this.armCamera(camera.cameraId, true);
    }
  }

  toggleSystem(group: CameraGroup): void {
    if (group.syncArmed) {
      this.requestDisarm({ kind: 'system', id: group.syncName, name: group.syncName });
    } else {
      this.armSystem(group.syncName, true);
    }
  }

  requestDisarm(request: DisarmRequest): void {
    this.pendingDisarm = request;
  }

  cancelDisarm(): void {
    this.pendingDisarm = null;
  }

  /**
   * Vor dem Schalten wird das Ziel aus der AKTUELLEN Liste neu aufgeloest und
   * nur fortgefahren, wenn es noch scharf ist — ein Hintergrund-Refresh bei
   * offenem Dialog darf nicht dazu fuehren, dass der Knopf etwas schaltet,
   * das laengst jemand anders geschaltet hat (Regel aus confirmToggle).
   */
  confirmDisarm(): void {
    const request = this.pendingDisarm;
    this.pendingDisarm = null;
    if (!request) {
      return;
    }
    if (request.kind === 'camera') {
      const current = this.cameras.find(c => c.cameraId === request.id);
      if (current?.armed) {
        this.armCamera(request.id, false);
      }
    } else {
      const group = this.groups.find(g => g.syncName === request.id);
      if (group?.syncArmed) {
        this.armSystem(request.id, false);
      }
    }
  }

  private armCamera(cameraId: string, armed: boolean): void {
    this.armBusy.add(cameraId);
    this.blinkService.setCameraArmed(cameraId, armed).subscribe({
      next: () => { this.armBusy.delete(cameraId); this.load(true); },
      error: () => { this.armBusy.delete(cameraId); }
    });
  }

  private armSystem(syncName: string, armed: boolean): void {
    const key = `sync:${syncName}`;
    this.armBusy.add(key);
    this.blinkService.setSystemArmed(syncName, armed).subscribe({
      next: () => { this.armBusy.delete(key); this.load(true); },
      error: () => { this.armBusy.delete(key); }
    });
  }

  private load(silent: boolean): void {
    this.blinkService.getCameras().subscribe({
      next: cameras => {
        this.cameras = cameras;
        this.groups = this.groupBySync(cameras);
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

  // Muster CamerasComponent.groupBySync — bewusst 1:1 uebernommen statt
  // ausgelagert, siehe Projektlinie: Website- und Tablet-Variante entwickeln
  // sich unabhaengig.
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
