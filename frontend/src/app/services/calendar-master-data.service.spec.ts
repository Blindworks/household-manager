import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { CalendarMasterDataService } from './calendar-master-data.service';
import { CalendarCategory } from '../models/calendar-category.model';
import { HouseholdUser } from '../models/household-user.model';

const CATEGORIES: CalendarCategory[] = [
  { id: 8, key: 'archiv', name: 'Archiv', color: '#90a4ae', icon: null, sortOrder: 1, active: false },
  { id: 3, key: 'health', name: 'Gesundheit', color: '#e57373', icon: null, sortOrder: 3, active: true },
  { id: 4, key: 'household', name: 'Haushalt', color: '#81c784', icon: null, sortOrder: 4, active: true }
];

const USERS: HouseholdUser[] = [
  { id: 10, displayName: 'Benedikt', enabled: true },
  { id: 11, displayName: 'Anna', enabled: true },
  { id: 12, displayName: 'Ausgezogen', enabled: false }
];

describe('CalendarMasterDataService', () => {
  let service: CalendarMasterDataService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(CalendarMasterDataService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  /** Beantwortet beide Abrufe; null als Antwort laesst den jeweiligen Abruf scheitern. */
  function load(categories: CalendarCategory[] | null, users: HouseholdUser[] | null): void {
    service.load();
    const categoryRequest = httpMock.expectOne('/api/v1/calendar/categories');
    if (categories) {
      categoryRequest.flush(categories);
    } else {
      categoryRequest.flush({ message: 'Datenbank nicht erreichbar' },
        { status: 500, statusText: 'Server Error' });
    }
    const userRequest = httpMock.expectOne('/api/v1/users');
    if (users) {
      userRequest.flush(users);
    } else {
      // Ohne strukturierten Body greift der Fallbacktext aus HouseholdUserService.
      userRequest.flush(null, { status: 500, statusText: 'Server Error' });
    }
  }

  it('stellt beide Stammdatenquellen bereit', () => {
    load(CATEGORIES, USERS);

    expect(service.categories()).toEqual(CATEGORIES);
    expect(service.users()).toEqual(USERS);
    expect(service.categoriesLoaded()).toBeTrue();
    expect(service.categoryError()).toBeNull();
    expect(service.userError()).toBeNull();
  });

  it('haelt auch die deaktivierten Eintraege vor', () => {
    // Bestandstermine brauchen die Farbe ihrer deaktivierten Kategorie weiter, und
    // "(deaktiviert)" an einer Person laesst sich nur mit der vollen Liste belegen.
    load(CATEGORIES, USERS);

    expect(service.categories().map(category => category.id)).toContain(8);
    expect(service.users().map(user => user.id)).toContain(12);
  });

  it('activeCategories und activeUsers lassen die deaktivierten weg', () => {
    load(CATEGORIES, USERS);

    expect(service.activeCategories().map(category => category.id)).toEqual([3, 4]);
    expect(service.activeUsers().map(user => user.id)).toEqual([10, 11]);
  });

  it('ein Kategorien-Ausfall haelt categoriesLoaded auf false', () => {
    // Das ist die tragende Zusicherung: der Termindialog oeffnet nur bei geladenen
    // Kategorien. Eine leere Auswahlliste saehe aus wie "es gibt keine Kategorien".
    load(null, USERS);

    expect(service.categoriesLoaded()).toBeFalse();
    expect(service.categoryError()).toContain('Neue Termine lassen sich gerade nicht anlegen');
  });

  it('die Meldung des Kategorien-Ausfalls nennt die Ursache aus dem Backend', () => {
    // Ohne sie steht im Supportfall auf dem Wandtablet nur der generische Satz.
    load(null, USERS);

    expect(service.categoryError()).toContain('Datenbank nicht erreichbar');
  });

  it('ein Nutzer-Ausfall leert die Liste und meldet ihn eigenstaendig', () => {
    load(CATEGORIES, null);

    expect(service.users()).toEqual([]);
    expect(service.activeUsers()).toEqual([]);
    expect(service.userError()).toBeTruthy();
  });

  it('ein Nutzer-Ausfall laesst die Kategorien unberuehrt', () => {
    // Die beiden Fehlerpolitiken sind bewusst verschieden: ein Termin ohne Personen ist
    // gueltig, also darf der Nutzer-Ausfall den Dialog nicht sperren.
    load(CATEGORIES, null);

    expect(service.categoriesLoaded()).toBeTrue();
    expect(service.categoryError()).toBeNull();
  });

  it('ein Kategorien-Ausfall laesst die Nutzerliste unberuehrt', () => {
    load(null, USERS);

    expect(service.users()).toEqual(USERS);
    expect(service.userError()).toBeNull();
  });

  it('ein erfolgreicher zweiter Abruf raeumt den Fehler des ersten weg', () => {
    load(null, null);
    expect(service.categoryError()).toBeTruthy();
    expect(service.userError()).toBeTruthy();

    load(CATEGORIES, USERS);

    expect(service.categoryError()).toBeNull();
    expect(service.userError()).toBeNull();
    expect(service.categoriesLoaded()).toBeTrue();
  });
});
