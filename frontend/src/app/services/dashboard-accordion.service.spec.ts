import { TestBed } from '@angular/core/testing';
import { DashboardAccordionService } from './dashboard-accordion.service';

const STORAGE_KEY = 'household-manager-dashboard-tile';

describe('DashboardAccordionService', () => {
  function createService(): DashboardAccordionService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    return TestBed.inject(DashboardAccordionService);
  }

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  afterAll(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  it('startet ohne gespeicherten Zustand mit der Schalter-Kachel', () => {
    const service = createService();

    expect(service.isOpen('switches')).toBeTrue();
    expect(service.isOpen('consumers')).toBeFalse();
  });

  it('oeffnet nur eine Kachel: die vorher offene schliesst sich', () => {
    const service = createService();

    service.toggle('consumers');

    expect(service.isOpen('consumers')).toBeTrue();
    expect(service.isOpen('switches')).toBeFalse();
  });

  it('schliesst die bereits offene Kachel beim erneuten Antippen', () => {
    const service = createService();

    service.toggle('switches');

    expect(service.openTile()).toBeNull();
  });

  it('stellt die zuletzt offene Kachel nach einem Reload wieder her', () => {
    createService().toggle('consumers');

    expect(createService().isOpen('consumers')).toBeTrue();
  });

  it('stellt auch den Zustand "alle zu" wieder her', () => {
    // 'switches' ist initial offen – ein Toggle schliesst alles.
    createService().toggle('switches');

    expect(createService().openTile()).toBeNull();
  });

  it('faellt bei einem unbekannten gespeicherten Wert auf "alle zu" zurueck', () => {
    localStorage.setItem(STORAGE_KEY, 'kitchen-sink');

    expect(createService().openTile()).toBeNull();
  });

  it('faellt auf "alle zu" zurueck, wenn noch die entfernte Klima-Kachel gespeichert ist', () => {
    localStorage.setItem(STORAGE_KEY, 'climate');

    expect(createService().openTile()).toBeNull();
  });
});
