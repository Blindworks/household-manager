import { TestBed } from '@angular/core/testing';
import { ViewModeService } from './view-mode.service';

describe('ViewModeService', () => {
  const KEY = 'household-manager-view-mode';

  beforeEach(() => {
    localStorage.removeItem(KEY);
    TestBed.configureTestingModule({});
  });

  afterEach(() => localStorage.removeItem(KEY));

  it('zeigt Wandtablet-Overlays nur bei Wandtablet-Konto und Tablet-Ansicht', () => {
    localStorage.setItem(KEY, 'website');
    const service = TestBed.inject(ViewModeService);
    const cases: [boolean, boolean, boolean][] = [
      [false, false, false], [false, true, false], [true, false, false], [true, true, true]
    ];

    for (const [wallTablet, tablet, expected] of cases) {
      service.setWallTabletAccount(wallTablet);
      service.isTabletView.set(tablet);
      expect(service.showWallTabletOverlays()).withContext(`${wallTablet}/${tablet}`).toBe(expected);
    }
  });

  it('ohne angemeldetes Wandtablet-Konto gibt es keine Overlays, auch in der Tablet-Ansicht', () => {
    localStorage.setItem(KEY, 'tablet');
    const service = TestBed.inject(ViewModeService);

    expect(service.isTabletView()).toBeTrue();
    expect(service.showWallTabletOverlays()).toBeFalse();
  });

  it('startet ein Wandtablet-Konto ohne gespeicherte Wahl in der Tablet-Ansicht, ohne zu speichern', () => {
    const service = TestBed.inject(ViewModeService);

    service.setWallTabletAccount(true);

    expect(service.isTabletView()).toBeTrue();
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('laesst eine gespeicherte Website-Wahl beim Wandtablet-Konto stehen', () => {
    localStorage.setItem(KEY, 'website');
    const service = TestBed.inject(ViewModeService);

    service.setWallTabletAccount(true);

    expect(service.isTabletView()).toBeFalse();
  });

  it('schaltet fuer normale Konten nicht automatisch um', () => {
    const service = TestBed.inject(ViewModeService);

    service.setWallTabletAccount(false);

    expect(service.isTabletView()).toBeFalse();
  });
});
