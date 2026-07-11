import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { FlowListComponent } from './flow-list.component';
import { FlowService } from '../../services/flow.service';

describe('FlowListComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService', ['getFlows', 'createFlow', 'deleteFlow', 'setEnabled', 'importFlow']);
    flowService.getFlows.and.returnValue(of([
      { id: 1, name: 'A', enabled: true, deployed: true },
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
});
