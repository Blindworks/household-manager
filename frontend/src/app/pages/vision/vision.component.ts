import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { VisionService } from '../../services/vision.service';
import { VisionPerson, VisionPhoto, VisionRecognition, VisionStatus } from '../../models/vision.model';

/** Seite "Gesichtserkennung": Blink-Status, Personenverwaltung, Erkennungshistorie (Anmeldung unter Admin → Konten). */
@Component({
  selector: 'app-vision',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './vision.component.html',
  styleUrl: './vision.component.scss'
})
export class VisionComponent implements OnInit {
  private readonly visionService = inject(VisionService);

  status: VisionStatus | null = null;
  persons: VisionPerson[] = [];
  recognitions: VisionRecognition[] = [];
  photosByPerson: Record<number, VisionPhoto[]> = {};
  expandedPersonId: number | null = null;
  editingPersonId: number | null = null;

  newPersonName = '';
  editName = '';
  /** Rueckmeldung zu einer vom Nutzer ausgeloesten Aktion (Banner oben). */
  errorMessage = '';
  /** Fehler der Hintergrund-Ladevorgaenge - bewusst je Abschnitt statt im Banner. */
  personsLoadError = '';
  recognitionsLoadError = '';

  ngOnInit(): void {
    this.reloadStatus();
    this.reloadPersons();
    this.reloadRecognitions();
  }

  reloadStatus(): void {
    this.visionService.getStatus().subscribe({
      next: status => this.status = status,
      error: () => this.status = null
    });
  }

  reloadPersons(): void {
    this.visionService.getPersons().subscribe({
      next: persons => { this.persons = persons; this.personsLoadError = ''; },
      error: err => this.personsLoadError = err.message
    });
  }

  reloadRecognitions(): void {
    this.visionService.getRecognitions().subscribe({
      next: recognitions => { this.recognitions = recognitions; this.recognitionsLoadError = ''; },
      error: err => this.recognitionsLoadError = err.message
    });
  }

  createPerson(): void {
    const name = this.newPersonName.trim();
    if (!name) {
      return;
    }
    this.errorMessage = '';
    this.visionService.createPerson(name).subscribe({
      next: () => { this.newPersonName = ''; this.reloadPersons(); },
      error: err => this.errorMessage = err.message
    });
  }

  startRename(person: VisionPerson): void {
    this.editingPersonId = person.id;
    this.editName = person.name;
  }

  cancelRename(): void {
    this.editingPersonId = null;
    this.editName = '';
  }

  saveRename(person: VisionPerson): void {
    const name = this.editName.trim();
    if (!name || name === person.name) {
      this.cancelRename();
      return;
    }
    this.errorMessage = '';
    this.visionService.updatePerson(person.id, name, person.active).subscribe({
      // Bei einem Fehler bleibt die Zeile im Bearbeitungsmodus, damit der Nutzer
      // den Namen korrigieren kann statt seine Eingabe zu verlieren.
      next: () => { this.cancelRename(); this.reloadPersons(); },
      error: err => this.errorMessage = err.message
    });
  }

  /** Inaktive Personen bleiben samt Fotos erhalten, werden aber nicht mehr erkannt. */
  toggleActive(person: VisionPerson): void {
    this.errorMessage = '';
    this.visionService.updatePerson(person.id, person.name, !person.active).subscribe({
      next: () => this.reloadPersons(),
      error: err => this.errorMessage = err.message
    });
  }

  deletePerson(person: VisionPerson): void {
    if (!confirm(`Person "${person.name}" samt Fotos löschen?`)) {
      return;
    }
    this.errorMessage = '';
    this.visionService.deletePerson(person.id).subscribe({
      next: () => this.reloadPersons(),
      error: err => this.errorMessage = err.message
    });
  }

  togglePhotos(person: VisionPerson): void {
    if (this.expandedPersonId === person.id) {
      this.expandedPersonId = null;
      return;
    }
    this.expandedPersonId = person.id;
    this.reloadPhotos(person);
  }

  uploadPhoto(person: VisionPerson, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.errorMessage = '';
    this.visionService.uploadPhoto(person.id, file).subscribe({
      next: () => { input.value = ''; this.reloadPersons(); this.reloadPhotos(person); },
      error: err => this.errorMessage = err.message
    });
  }

  deletePhoto(person: VisionPerson, photo: VisionPhoto): void {
    this.errorMessage = '';
    this.visionService.deletePhoto(person.id, photo.id).subscribe({
      next: () => { this.reloadPersons(); this.reloadPhotos(person); },
      error: err => this.errorMessage = err.message
    });
  }

  /** Nur nach einer Nutzeraktion aufgerufen (Fotoliste oeffnen, Upload/Loeschen)
   * -> Fehler gehoert deshalb ins Aktions-Banner. */
  private reloadPhotos(person: VisionPerson): void {
    this.visionService.getPhotos(person.id).subscribe({
      next: photos => this.photosByPerson[person.id] = photos,
      error: err => this.errorMessage = err.message
    });
  }
}
