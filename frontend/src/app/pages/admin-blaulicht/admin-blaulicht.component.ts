import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { BlaulichtService } from '../../services/blaulicht.service';
import { BlaulichtSettings } from '../../models/blaulicht.model';

/** Formularstand: Listen als Text mit einem Eintrag je Zeile. */
interface BlaulichtForm extends Omit<BlaulichtSettings, 'districts' | 'neighbors' | 'excludedProviders'> {
  districts: string;
  neighbors: string;
  excludedProviders: string;
}

export function toForm(s: BlaulichtSettings): BlaulichtForm {
  return {
    ...s,
    districts: s.districts.join('\n'),
    neighbors: s.neighbors.join('\n'),
    excludedProviders: s.excludedProviders.join('\n')
  };
}

/** Eine Zeile je Eintrag; Kommas trennen ebenfalls, leere Zeilen fallen weg. */
export function parseList(text: string): string[] {
  return text.split(/[\n,]/).map(v => v.trim()).filter(v => v.length > 0);
}

export function toSettings(f: BlaulichtForm): BlaulichtSettings {
  return {
    ...f,
    districts: parseList(f.districts),
    neighbors: parseList(f.neighbors),
    excludedProviders: parseList(f.excludedProviders)
  };
}

/**
 * Admin-Seite „Blaulicht“: Quellen an/aus, Orte, Regionalschlüssel und die Uhrzeiten von Briefing und
 * Nachtrag. Gespeichert wird der ganze Satz; das Backend prüft und meldet Fehler im Klartext (400).
 */
@Component({
  selector: 'app-admin-blaulicht',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './admin-blaulicht.component.html',
  styleUrl: './admin-blaulicht.component.scss'
})
export class AdminBlaulichtComponent implements OnInit {
  private readonly api = inject(BlaulichtService);

  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly message = signal<string | null>(null);
  readonly messageType = signal<'success' | 'error' | null>(null);

  form: BlaulichtForm | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.api.getSettings().subscribe({
      next: settings => {
        this.form = toForm(settings);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
      }
    });
  }

  save(): void {
    if (!this.form || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.message.set(null);
    this.api.saveSettings(toSettings(this.form)).subscribe({
      next: saved => {
        this.form = toForm(saved);
        this.saving.set(false);
        this.messageType.set('success');
        this.message.set('Gespeichert. Gilt ab dem nächsten Abruf, die Hub-Karte ist schon aktualisiert.');
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.messageType.set('error');
        this.message.set(errorText(err));
      }
    });
  }
}

function errorText(err: HttpErrorResponse): string {
  const body = err.error as { message?: string } | string | null;
  if (typeof body === 'string' && body.trim()) {
    return body;
  }
  if (body && typeof body === 'object' && body.message) {
    return body.message;
  }
  return 'Speichern fehlgeschlagen.';
}
