import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { FlowListComponent } from './flow-list.component';
import { FlowService } from '../../services/flow.service';
import { COLLAPSED_SECTIONS_KEY } from './flow-section-storage.util';

describe('FlowListComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(() => {
    localStorage.removeItem(COLLAPSED_SECTIONS_KEY);
  });

  afterEach(() => localStorage.removeItem(COLLAPSED_SECTIONS_KEY));

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService', ['getFlows', 'createFlow', 'deleteFlow', 'setEnabled', 'importFlow']);
    flowService.getFlows.and.returnValue(of([
      { id: 1, name: 'A', enabled: true, deployed: true, lastTriggeredAt: '2020-01-02T08:15:00' },
      { id: 2, name: 'B', enabled: false, deployed: false }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [FlowListComponent],
      providers: [provideRouter([]), { provide: FlowService, useValue: flowService }]
    }).compileComponents();
  });

  it('loads flows on init', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.flows().length).toBe(2);
  });

  it('shows when each flow was last triggered', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    const cells = Array.from(fixture.nativeElement.querySelectorAll('.flow-table__last-triggered'))
      .map(cell => (cell as HTMLElement).textContent?.trim());
    expect(cells).toEqual(['02.01.2020, 08:15', 'Nie']);
  });

  it('deletes a flow and reloads', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    flowService.deleteFlow.and.returnValue(of(void 0));
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    fixture.componentInstance.deleteFlow({ id: 1, name: 'A', enabled: true, deployed: true } as any);
    expect(flowService.deleteFlow).toHaveBeenCalledWith(1);
    expect(flowService.getFlows).toHaveBeenCalledTimes(2);
  });

  it('imports a flow from text and navigates to it', () => {
    flowService.importFlow.and.returnValue(of({ id: 5, name: 'X', enabled: false, deployed: false } as any));
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    fixture.componentInstance['importFromText']('{"schemaVersion":1,"name":"X","definition":{"nodes":[],"wires":[]}}');

    expect(flowService.importFlow).toHaveBeenCalledWith({
      schemaVersion: 1, name: 'X', definition: { nodes: [], wires: [] }
    });
    expect(navigate).toHaveBeenCalledWith(['/flows', 5]);
  });

  it('shows an error and does not post when the file is not valid JSON', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    fixture.componentInstance['importFromText']('{ not json');

    expect(flowService.importFlow).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toBeTruthy();
  });

  describe('sections', () => {
    beforeEach(() => {
      flowService.getFlows.and.returnValue(of([
        { id: 1, name: 'Nachtmodus', category: 'Modi & Szenen', enabled: true, deployed: true },
        { id: 2, name: 'Büro-Taster', category: 'Taster', enabled: false, deployed: true },
        { id: 3, name: 'Diagnose', enabled: true, deployed: true, description: 'prüft die Engine' },
        { id: 4, name: 'Morgenmodus', category: 'Modi & Szenen', enabled: true, deployed: true }
      ] as any));
    });

    function render() {
      const fixture = TestBed.createComponent(FlowListComponent);
      fixture.detectChanges();
      return fixture;
    }

    function sectionTitles(fixture: any): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('.flow-section__title'))
        .map(el => (el as HTMLElement).textContent!.trim());
    }

    function rowNames(fixture: any): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('.flow-table__row td:first-child'))
        .map(el => (el as HTMLElement).textContent!.trim());
    }

    it('renders one section per category with "Sonstiges" last', () => {
      const fixture = render();

      expect(sectionTitles(fixture)).toEqual(['Modi & Szenen', 'Taster', 'Sonstiges']);
      expect(rowNames(fixture)).toEqual(['Morgenmodus', 'Nachtmodus', 'Büro-Taster', 'Diagnose']);
    });

    it('shows the count and a hint for disabled flows in the section header', () => {
      const fixture = render();
      const headers = Array.from(fixture.nativeElement.querySelectorAll('.flow-section__toggle')) as HTMLElement[];

      expect(headers[0].querySelector('.flow-section__count')!.textContent).toContain('2');
      expect(headers[0].querySelector('.flow-section__hint')).toBeNull();
      expect(headers[1].querySelector('.flow-section__hint')).not.toBeNull();
    });

    it('collapses a section and remembers it across page loads', () => {
      const fixture = render();
      (fixture.nativeElement.querySelector('.flow-section__toggle') as HTMLElement).click();
      fixture.detectChanges();

      expect(rowNames(fixture)).toEqual(['Büro-Taster', 'Diagnose']);

      const reloaded = render();
      expect(rowNames(reloaded)).toEqual(['Büro-Taster', 'Diagnose']);
    });

    it('search filters rows, hides empty sections and ignores collapsed state', () => {
      localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify(['Sonstiges']));
      const fixture = render();

      fixture.componentInstance.query.set('engine');
      fixture.detectChanges();

      expect(sectionTitles(fixture)).toEqual(['Sonstiges']);
      expect(rowNames(fixture)).toEqual(['Diagnose']);
    });

    it('says so when the search finds nothing', () => {
      const fixture = render();
      fixture.componentInstance.query.set('gibtsnicht');
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.flow-table__empty').textContent).toContain('Keine Treffer');
    });

    it('still renders all sections expanded when storage throws', () => {
      spyOn(Storage.prototype, 'getItem').and.throwError('blocked');
      const fixture = render();

      expect(rowNames(fixture).length).toBe(4);
    });
  });
});
